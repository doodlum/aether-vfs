//! Several client threads on one ring: they do not wait for each other, a
//! request that times out cannot hand its late reply to a later one, and the
//! data gate keeps a worker free for requests that are not data.
//!
//! Everything here runs the shipped endpoints (`RingClient`, `RingServer`,
//! `read_fragmented`, `DataGate`) over an in-process segment with real server
//! threads; only the handler is the test's own.

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use vfs_ipc::layout::{ST_ABANDONED, ST_FREE, ST_PROCESSING, ST_SUBMITTED};
use vfs_ipc::ring::{self, Geom, IpcError};
use vfs_ipc::{
    read_fragmented, DataArena, DataGate, Notifier, OwnedSeg, ReadPlan, RingClient, RingServer,
    SharedSeg, SpinNotifier, CLIENT_SPIN_BUDGET,
};
use vfs_protocol as P;

/// Echo the payload back at once.
const OP_ECHO: u32 = 900;

/// Block until the test opens the latch ([`Fixture::open_latch`]), then
/// answer `b"released"`.
const OP_HOLD: u32 = 902;
/// Once the client has published (see [`NapClock`]), add the payload's `u64`
/// milliseconds to [`Fixture::napped_ms`], then answer `b"napped"`. Time that
/// passes for a [`NapClock`] client and for nothing else.
const OP_NAP: u32 = 903;

/// Reads of this handle block until the test opens the latch: a provider
/// that has stopped answering, for exactly as long as the test says.
const FH_HELD: u64 = 98;
/// How long a test waits for something that must happen before it calls the
/// run a failure. Never part of a passing run's logic.
const GIVE_UP: Duration = Duration::from_secs(20);

const PAYLOAD_CAP: u32 = 4096;
const BANK: usize = 64 * 1024;

/// Byte `i` of file `fh`. Depends on both, with a prime period, so a read
/// answered with another handle's bytes or at another offset is caught.
fn byte(fh: u64, i: u64) -> u8 {
    ((i % 251) as u8) ^ (fh as u8).wrapping_mul(37)
}

struct Fixture {
    owned: OwnedSeg,
    geom: Geom,
    arena_off: usize,
    arena_len: usize,
    stop: AtomicBool,
    /// Closed until the test opens it; held requests wait on it.
    latch: Mutex<bool>,
    latch_opened: Condvar,
    /// Requests inside the handler waiting on the latch, and the most ever.
    held_now: AtomicU32,
    held_peak: AtomicU32,
    /// What the `OP_NAP` requests have added up to, and whether a
    /// [`NapClock`] client has published (the server naps only after).
    napped_ms: AtomicU64,
    client_notified: AtomicBool,
    client_published: AtomicBool,
}

impl Fixture {
    fn new(slots: u32) -> Self {
        let stride = (32 + PAYLOAD_CAP as usize + 7) & !7;
        let arena_off = vfs_ipc::layout::RING_HEADER_SIZE + slots as usize * stride;
        let arena_len = slots as usize * BANK;
        let owned = OwnedSeg::new(arena_off + arena_len);
        let geom = ring::init(owned.seg(), slots, PAYLOAD_CAP).unwrap();
        Fixture {
            owned,
            geom,
            arena_off,
            arena_len,
            stop: AtomicBool::new(false),
            latch: Mutex::new(false),
            latch_opened: Condvar::new(),
            held_now: AtomicU32::new(0),
            held_peak: AtomicU32::new(0),
            napped_ms: AtomicU64::new(0),
            client_notified: AtomicBool::new(false),
            client_published: AtomicBool::new(false),
        }
    }

    /// Server side: wait until the test opens the latch.
    fn hold(&self) {
        let now = self.held_now.fetch_add(1, Ordering::SeqCst) + 1;
        self.held_peak.fetch_max(now, Ordering::SeqCst);
        let mut open = self.latch.lock().unwrap();
        while !*open {
            open = self.latch_opened.wait(open).unwrap();
        }
        drop(open);
        self.held_now.fetch_sub(1, Ordering::SeqCst);
    }

    /// Let every held request, now and later, go.
    fn open_latch(&self) {
        *self.latch.lock().unwrap() = true;
        self.latch_opened.notify_all();
    }

    /// Wait until `ready` holds. Panics, naming `what`, if it never does.
    fn until(&self, what: &str, ready: impl Fn() -> bool) {
        let t = Instant::now();
        while !ready() {
            assert!(t.elapsed() < GIVE_UP, "never happened: {what}");
            thread::sleep(Duration::from_millis(1));
        }
    }

    fn held(&self) -> u32 {
        self.held_now.load(Ordering::SeqCst)
    }

    fn slots_in(&self, state: u32) -> usize {
        (0..self.geom.slot_count)
            .filter(|&s| self.state(s) == state)
            .count()
    }

    fn seg(&self) -> &SharedSeg {
        self.owned.seg()
    }

    fn client(&self) -> RingClient<'_, SpinNotifier> {
        RingClient::with_geom(self.seg(), self.geom, SpinNotifier)
    }

    /// A client on a clock the test drives: see [`TestClock`].
    fn client_clocked<F: Fn() -> Duration>(&self, passed: F) -> RingClient<'_, TestClock<F>> {
        RingClient::with_geom(self.seg(), self.geom, TestClock(Instant::now(), passed))
    }

    fn plan(&self) -> ReadPlan {
        ReadPlan {
            bulk_threshold: 8 * 1024,
            bulk_chunk: BANK,
            inline_chunk: PAYLOAD_CAP as usize - 8,
            depth: 4,
            depth_stream: 8,
            stream_bytes: 4 * BANK,
        }
    }

    /// One server thread's loop: what `IpcServe`'s worker does, with this
    /// test's handler in place of the director.
    fn serve(&self) {
        let ring = RingServer::new(self.seg(), SpinNotifier).unwrap();
        let arena = DataArena::new(
            self.seg(),
            self.arena_off,
            self.arena_len,
            self.geom.slot_count as usize,
        );
        while !self.stop.load(Ordering::Relaxed) {
            ring.serve_one(|req| match req.opcode {
                OP_ECHO => (P::ST_OK, req.payload.clone()),
                OP_NAP => {
                    self.until("the client published", || {
                        self.client_published.load(Ordering::SeqCst)
                    });
                    let ms = u64::from_le_bytes(req.payload[..8].try_into().unwrap());
                    self.napped_ms.fetch_add(ms, Ordering::SeqCst);
                    (P::ST_OK, b"napped".to_vec())
                }
                OP_HOLD => {
                    self.hold();
                    (P::ST_OK, b"released".to_vec())
                }
                P::OP_READ => {
                    let r = P::decode_read_req(&req.payload).unwrap();
                    if r.fh == FH_HELD {
                        self.hold();
                    }
                    let len = r.len as usize;
                    if req.flags & P::FLAG_READ_BULK != 0 {
                        let (off, n) = arena
                            .fill_bank(req.slot, len, |buf| {
                                for (j, b) in buf.iter_mut().enumerate() {
                                    *b = byte(r.fh, r.offset + j as u64);
                                }
                                Ok(buf.len())
                            })
                            .unwrap();
                        (P::ST_OK, P::encode_read_resp_bulk(n as u32, off))
                    } else {
                        let data: Vec<u8> =
                            (0..len).map(|j| byte(r.fh, r.offset + j as u64)).collect();
                        (P::ST_OK, P::encode_read_resp(&data))
                    }
                }
                _ => (P::ST_NOT_SUPPORTED, Vec::new()),
            })
            .unwrap();
        }
    }

    /// Run `body` with `workers` server threads serving the ring.
    fn with_workers<R>(&self, workers: usize, body: impl FnOnce() -> R) -> R {
        /// Lets the server threads go when `body` ends — or panics: a failed
        /// assertion must fail the test, not leave the scope waiting on
        /// workers that are still holding requests.
        struct Stop<'a>(&'a Fixture);
        impl Drop for Stop<'_> {
            fn drop(&mut self) {
                self.0.open_latch();
                self.0.stop.store(true, Ordering::Relaxed);
            }
        }
        thread::scope(|s| {
            for _ in 0..workers {
                s.spawn(|| self.serve());
            }
            let _stop = Stop(self);
            body()
        })
    }

    fn state(&self, slot: u32) -> u32 {
        ring::slot_state(self.seg(), &self.geom, slot).unwrap()
    }

    /// Wait (bounded) until `slot` is in `want`.
    fn await_state(&self, slot: u32, want: u32) {
        let t = Instant::now();
        while self.state(slot) != want {
            assert!(
                t.elapsed() < Duration::from_secs(5),
                "slot {slot} never reached state {want}; it is {}",
                self.state(slot)
            );
            thread::sleep(Duration::from_millis(1));
        }
    }
}

fn assert_bytes(fh: u64, offset: u64, got: &[u8]) {
    if let Some(i) = (0..got.len()).find(|&i| got[i] != byte(fh, offset + i as u64)) {
        panic!(
            "fh {fh} offset {offset}: byte {i} of {} is {}, want {}",
            got.len(),
            got[i],
            byte(fh, offset + i as u64)
        );
    }
}

/// A client notifier whose clock the test drives: `now()` is the moment it was
/// made plus however much time `.1` says has passed. Waits spin, as
/// [`SpinNotifier`]'s do.
///
/// A deadline these tests want to see expire then expires when the test says,
/// not when some milliseconds have gone by. That matters twice over here. A
/// request no worker has taken yet is simply freed when its client gives up
/// (`ring::abandon`), so the director never sees it — and on a loaded machine,
/// this binary's tests each spinning their own workers, no worker taking a
/// request within 40 ms is ordinary. And a client thread that does not run
/// until every reply is in finds them all there and returns `Ok`, deadline or
/// not.
struct TestClock<F: Fn() -> Duration>(Instant, F);

impl<F: Fn() -> Duration> Notifier for TestClock<F> {
    fn wait_client(&self, _slot: u32) {
        core::hint::spin_loop();
    }
    fn now(&self) -> Instant {
        self.0 + (self.1)()
    }
}

/// Past any deadline a test client has.
const LONG_AFTER: Duration = Duration::from_secs(3600);

/// A client notifier whose clock is the server's naps: `now()` is the moment it
/// was made plus [`Fixture::napped_ms`].
///
/// A batch is published, the server told (`notify_server`), and then the clock
/// read once for the batch's deadline. This notes that read as the moment the
/// batch was published, and `OP_NAP` waits for it: so the deadline is always
/// counted from no time at all, however late the client thread runs.
struct NapClock<'a>(&'a Fixture, Instant);

impl Notifier for NapClock<'_> {
    fn notify_server(&self) {
        self.0.client_notified.store(true, Ordering::SeqCst);
    }
    fn wait_client(&self, _slot: u32) {
        core::hint::spin_loop();
    }
    fn now(&self) -> Instant {
        if self.0.client_notified.load(Ordering::SeqCst) {
            self.0.client_published.store(true, Ordering::SeqCst);
        }
        self.1 + Duration::from_millis(self.0.napped_ms.load(Ordering::SeqCst))
    }
}

/// Eight threads, no lock between them, each reading its own handle in sizes
/// that travel inline, through one arena bank, and as a pipeline of banks.
/// Every read must come back whole and with its own handle's bytes: the slots
/// and the banks are per request, so nothing may leak across threads.
#[test]
fn concurrent_reads_from_many_threads_each_get_their_own_bytes() {
    let fx = Fixture::new(8);
    let gate = DataGate::new(vfs_ipc::data_limit(4, 8));
    let plan = fx.plan();
    fx.with_workers(4, || {
        thread::scope(|s| {
            for t in 0..8u64 {
                let (fx, gate, plan) = (&fx, &gate, &plan);
                s.spawn(move || {
                    let c = fx.client();
                    let fh = t + 1;
                    let sizes = [1usize, 700, 5_000, BANK, 3 * BANK + 123, 5 * BANK];
                    for i in 0..60usize {
                        let len = sizes[(i + t as usize) % sizes.len()];
                        let offset = (i as u64) * 1_000_003 + t;
                        let mut buf = vec![0u8; len];
                        let n = read_fragmented(&c, gate, plan, fh, offset, &mut buf).unwrap();
                        assert_eq!(n, len);
                        assert_bytes(fh, offset, &buf);
                    }
                });
            }
        });
    });
    assert_eq!(gate.in_flight(), 0, "every permit must come back");
    for slot in 0..8 {
        assert_eq!(fx.state(slot), ST_FREE, "slot {slot} was left held");
    }
}

/// One thread's request is held by the server for as long as the test likes.
/// Another thread's requests, made meanwhile, are answered as if it were not
/// there: all five hundred return while the first is still held.
#[test]
fn a_slow_request_does_not_hold_up_a_fast_one() {
    let fx = Fixture::new(8);
    fx.with_workers(4, || {
        thread::scope(|s| {
            let slow = s.spawn(|| fx.client().submit(OP_HOLD, 0, b"").unwrap().payload);
            fx.until("the held request reached the server", || fx.held() == 1);
            let c = fx.client();
            for i in 0..500u32 {
                let payload = i.to_le_bytes();
                assert_eq!(c.submit(OP_ECHO, 0, &payload).unwrap().payload, payload);
            }
            assert_eq!(fx.held(), 1, "the held request is still held");
            assert!(!slow.is_finished());
            fx.open_latch();
            assert_eq!(slow.join().unwrap(), b"released");
        });
    });
}

/// Six threads read a file whose every read blocks its server thread, on a
/// ring with four workers. Ungated, four of them would hold all four workers
/// and nothing else would be answered until one returned. The gate admits
/// three, so the fourth worker keeps answering.
#[test]
fn reads_held_at_the_gate_leave_a_worker_for_other_requests() {
    let fx = Fixture::new(8);
    let gate = DataGate::new(vfs_ipc::data_limit(4, 8));
    assert_eq!(gate.limit(), 3);
    let plan = fx.plan();
    fx.with_workers(4, || {
        thread::scope(|s| {
            for t in 0..6u64 {
                let (fx, gate, plan) = (&fx, &gate, &plan);
                s.spawn(move || {
                    let c = fx.client();
                    let mut buf = vec![0u8; 100];
                    let n = read_fragmented(&c, gate, plan, FH_HELD, t, &mut buf).unwrap();
                    assert_eq!(n, 100);
                    assert_bytes(FH_HELD, t, &buf);
                });
            }
            fx.until("three reads with the server, three in line", || {
                fx.held() == 3 && gate.waiting() == 3
            });
            assert_eq!(gate.in_flight(), 3);

            let c = fx.client();
            for i in 0..200u32 {
                let payload = i.to_le_bytes();
                assert_eq!(c.submit(OP_ECHO, 0, &payload).unwrap().payload, payload);
            }
            assert_eq!(fx.held(), 3, "the reads are all still blocked");
            fx.open_latch();
        });
    });
    assert_eq!(
        fx.held_peak.load(Ordering::SeqCst),
        3,
        "the server must never have held more reads than the gate admits"
    );
    assert_eq!(gate.in_flight(), 0);
    assert_eq!(gate.waiting(), 0);
}

/// **A read that timed out still holds its worker, and the gate knows.**
///
/// Four workers, so three data requests at once. A provider stops answering.
/// Three reads time out one after another; the director is still inside all
/// three. A fourth read must not be sent — it would take the last worker —
/// and a request that is not data must still be answered.
///
/// The gate used to give a timed-out read's permit straight back. Then the
/// fourth read was sent, every worker was held, and the echo below got no
/// answer for as long as the provider stayed stuck.
#[test]
fn reads_that_timed_out_keep_their_permits_until_the_director_is_done() {
    let fx = Fixture::new(8);
    let gate = DataGate::new(vfs_ipc::data_limit(4, 8));
    let plan = fx.plan();
    fx.with_workers(4, || {
        let impatient = fx.client().with_deadline(Duration::from_millis(40));
        let mut buf = vec![0u8; 100];
        for sent in 1..=3u32 {
            let impatient = fx
                .client_clocked(|| {
                    if fx.held() >= sent {
                        LONG_AFTER
                    } else {
                        Duration::ZERO
                    }
                })
                .with_deadline(Duration::from_millis(40));
            let r = read_fragmented(&impatient, &gate, &plan, FH_HELD, 0, &mut buf);
            assert_eq!(r, Err(P::ST_IO_ERROR));
            assert_eq!(fx.held(), sent, "the director is still inside read {sent}");
            assert_eq!(gate.in_flight(), sent, "so its permit is still out");
            assert_eq!(gate.retired(), sent);
        }
        assert_eq!(fx.slots_in(ST_ABANDONED), 3);

        // The fourth: refused at the gate, never sent.
        let r = read_fragmented(&impatient, &gate, &plan, FH_HELD, 0, &mut buf);
        assert_eq!(r, Err(P::ST_IO_ERROR));
        assert_eq!(
            fx.slots_in(ST_ABANDONED),
            3,
            "a fourth request reached the ring"
        );
        assert_eq!(fx.held(), 3);
        assert_eq!(gate.waiting(), 0, "it left the line when it gave up");

        // So the fourth worker is free, with the provider still stuck.
        let c = fx.client().with_deadline(GIVE_UP);
        assert_eq!(c.submit(OP_ECHO, 0, b"stat").unwrap().payload, b"stat");
        assert_eq!(fx.held(), 3);

        // The provider answers at last. The director drains the three slots,
        // and the next read that finds no permit takes theirs back.
        fx.open_latch();
        fx.until("the retired slots were drained", || {
            fx.slots_in(ST_ABANDONED) == 0
        });
        for i in 0..4u64 {
            let patient = fx.client().with_deadline(GIVE_UP);
            let n = read_fragmented(&patient, &gate, &plan, 7, i, &mut buf).unwrap();
            assert_eq!(n, 100);
            assert_bytes(7, i, &buf);
        }
        assert_eq!(
            gate.reclaim(fx.seg(), &fx.geom),
            0,
            "nothing left to take back"
        );
        assert_eq!(gate.retired(), 0);
        assert_eq!(gate.in_flight(), 0);
    });
    assert_eq!(fx.held_peak.load(Ordering::SeqCst), 3);
    assert_eq!(fx.slots_in(ST_FREE), 8);
}

/// **Two deep reads of content that is not coming do not take the gate.**
///
/// Sixteen workers, so twelve data requests at once. One thread reads eight
/// chunks of a blocked file and another four. Then a third thread reads one
/// byte of a file that is served at once.
///
/// The two pipelines used to be given 8 and 4 permits — all twelve — and the
/// third thread's read waited at the gate for as long as they ran. Now one
/// call holds at most half the gate and stops at the reserve, so they hold 6
/// and 3, and the small read goes straight through while both are still
/// blocked.
#[test]
fn two_deep_reads_of_blocked_content_leave_room_for_a_small_read() {
    let fx = Fixture::new(32);
    let gate = DataGate::new(vfs_ipc::data_limit(16, 32));
    assert_eq!((gate.limit(), gate.per_call(), gate.reserve()), (12, 6, 3));
    let plan = fx.plan();
    fx.with_workers(16, || {
        thread::scope(|s| {
            for chunks in [8usize, 4] {
                let (fx, gate, plan) = (&fx, &gate, &plan);
                s.spawn(move || {
                    let c = fx.client();
                    let mut buf = vec![0u8; chunks * BANK];
                    let n = read_fragmented(&c, gate, plan, FH_HELD, 0, &mut buf).unwrap();
                    assert_eq!(n, buf.len());
                    assert_bytes(FH_HELD, 0, &buf);
                });
                // One at a time, so which pipeline asked first is not left to
                // the scheduler: the eight-chunk read, then the four.
                let want = if chunks == 8 { 6 } else { 9 };
                fx.until("the pipeline's first batch reached the server", || {
                    fx.held() == want
                });
            }
            assert_eq!(
                gate.in_flight(),
                9,
                "6 for the first pipeline, 3 for the second"
            );
            assert_eq!(gate.waiting(), 0);

            // Both blocked. A zero-length wait at the gate would fail this
            // read, so that it succeeds is the assertion; the deadline only
            // bounds a failing run.
            let c = fx.client().with_deadline(GIVE_UP);
            let mut one = [0u8; 1];
            for i in 0..50u64 {
                let n = read_fragmented(&c, &gate, &plan, 7, i, &mut one).unwrap();
                assert_eq!((n, one[0]), (1, byte(7, i)));
                assert_eq!(fx.held(), 9, "both pipelines are still blocked");
            }
            fx.open_latch();
        });
    });
    assert!(
        fx.held_peak.load(Ordering::SeqCst) <= 9,
        "the pipelines held more than nine workers between them"
    );
    assert_eq!(gate.in_flight(), 0);
}

/// **The late reply, end to end.** A bulk read outlives its client's
/// deadline. The client gets `Timeout`; the slot is retired, not freed; every
/// request made while the server is still writing that slot — and its arena
/// bank — goes to another slot and gets its own bytes; and once the server
/// finishes, the slot is free and serves a request correctly.
#[test]
fn a_timed_out_request_retires_its_slot_until_the_late_reply_is_drained() {
    let fx = Fixture::new(2);
    let gate = DataGate::new(8);
    let plan = fx.plan();
    fx.with_workers(2, || {
        // The provider is stuck (`FH_HELD`) until the test lets it go, and the
        // client's deadline passes only once the director is inside the read.
        let impatient = fx
            .client_clocked(|| {
                if fx.held() > 0 {
                    LONG_AFTER
                } else {
                    Duration::ZERO
                }
            })
            .with_deadline(Duration::from_millis(40));
        let mut buf = vec![0u8; BANK];
        let r = read_fragmented(&impatient, &gate, &plan, FH_HELD, 0, &mut buf);
        assert_eq!(r, Err(P::ST_IO_ERROR), "the read must fail, not hang");
        assert_eq!(
            fx.held(),
            1,
            "it gave up while the director was still inside"
        );
        assert_eq!(
            gate.in_flight(),
            1,
            "the director still has the request, so its permit stays out"
        );
        assert_eq!(gate.retired(), 1);

        // The server still has the request: the slot is out of use.
        let retired = (0..2).find(|&s| fx.state(s) == ST_ABANDONED);
        let retired = retired.expect("the timed-out slot must be ABANDONED, not FREE");

        // Reads made meanwhile — the same size, so they use an arena bank —
        // never land in the retired slot and never see the held file's bytes.
        let c = fx.client();
        for during in 0..16u64 {
            let fh = 7;
            let offset = during * 13;
            let n = read_fragmented(&c, &gate, &plan, fh, offset, &mut buf).unwrap();
            assert_eq!(n, BANK);
            assert_bytes(fh, offset, &buf);
            assert_eq!(
                fx.state(retired),
                ST_ABANDONED,
                "still retired after read {during}"
            );
        }

        // The provider answers at last; the server writes its late reply.
        fx.open_latch();

        // The server finished and drained it: free, and usable.
        fx.await_state(retired, ST_FREE);
        let (resps, held) = c
            .submit_many_held(&[
                (OP_ECHO, 0, b"first".to_vec()),
                (OP_ECHO, 0, b"second".to_vec()),
            ])
            .unwrap();
        assert!(held.contains(&retired), "both slots are in use again");
        assert_eq!(resps[0].payload, b"first");
        assert_eq!(resps[1].payload, b"second");
        c.release_slots(&held);
        assert_eq!(gate.reclaim(fx.seg(), &fx.geom), 1, "its permit comes back");
        assert_eq!(gate.in_flight(), 0);
    });
    assert_eq!(fx.state(0), ST_FREE);
    assert_eq!(fx.state(1), ST_FREE);
}

/// A request no server ever takes: the client times out and the slot is free
/// at once, because nothing can still write to it.
#[test]
fn a_request_nobody_serves_times_out_and_frees_its_slot() {
    let fx = Fixture::new(2);
    let c = fx.client().with_deadline(Duration::from_millis(30));
    for _ in 0..5 {
        assert_eq!(
            c.submit(OP_ECHO, 0, b"hello").err(),
            Some(IpcError::Timeout)
        );
    }
    assert_eq!(fx.state(0), ST_FREE);
    assert_eq!(fx.state(1), ST_FREE);
}

/// A pipelined batch in which one request outlives the deadline: the whole
/// batch fails, the answered slots are freed, and only the slot the server
/// still holds is retired.
#[test]
fn a_batch_that_times_out_gives_every_slot_back() {
    let fx = Fixture::new(4);
    fx.with_workers(3, || {
        // The deadline passes once the held request is in the handler and is
        // the only one still in play: the echoes were answered, so none of
        // them can be mid-flight when it does.
        let c = fx
            .client_clocked(|| {
                let in_play = fx.held() == 1
                    && fx.slots_in(ST_PROCESSING) == 1
                    && fx.slots_in(ST_SUBMITTED) == 0;
                if in_play {
                    LONG_AFTER
                } else {
                    Duration::ZERO
                }
            })
            .with_deadline(Duration::from_millis(40));
        let r = c.submit_many_held(&[
            (OP_ECHO, 0, b"a".to_vec()),
            (OP_HOLD, 0, Vec::new()),
            (OP_ECHO, 0, b"c".to_vec()),
        ]);
        assert_eq!(r.err(), Some(IpcError::Timeout));
        let states: Vec<u32> = (0..4).map(|s| fx.state(s)).collect();
        assert_eq!(
            states.iter().filter(|&&s| s == ST_ABANDONED).count(),
            1,
            "exactly the held request's slot is retired: {states:?}"
        );
        assert_eq!(
            states.iter().filter(|&&s| s == ST_FREE).count(),
            3,
            "{states:?}"
        );
        let retired = states.iter().position(|&s| s == ST_ABANDONED).unwrap() as u32;
        fx.open_latch();
        fx.await_state(retired, ST_FREE);
    });
}

/// A client notifier that records what it is asked to do while waiting.
struct Watching {
    idle_calls: AtomicU32,
    /// The shortest `waited` an `idle_client` call was ever given, in µs.
    least_waited_us: AtomicU32,
}

impl Notifier for &Watching {
    fn wait_client(&self, _slot: u32) {
        core::hint::spin_loop();
    }
    fn idle_client(&self, _slot: u32, waited: Duration) {
        self.idle_calls.fetch_add(1, Ordering::Relaxed);
        self.least_waited_us
            .fetch_min(waited.as_micros() as u32, Ordering::Relaxed);
        thread::sleep(Duration::from_millis(1));
    }
}

/// A response that is late is waited for through `idle_client` — where the
/// shim yields and sleeps — and only once the spin budget is spent. The reply
/// is still collected, so the sleeping costs latency at most, never the
/// answer.
///
/// The provider is held until the client has gone idle a few times, so the
/// wait is late by construction rather than by a sleep a descheduled client
/// thread could sleep straight through.
#[test]
fn a_late_response_is_waited_for_off_the_processor() {
    let fx = Fixture::new(2);
    let w = Watching {
        idle_calls: AtomicU32::new(0),
        least_waited_us: AtomicU32::new(u32::MAX),
    };
    let waited = fx.with_workers(1, || {
        thread::scope(|s| {
            s.spawn(|| {
                fx.until("the client went idle", || {
                    w.idle_calls.load(Ordering::Relaxed) >= 3
                });
                fx.open_latch();
            });
            let c = RingClient::with_geom(fx.seg(), fx.geom, &w);
            let t = Instant::now();
            let r = c.submit(OP_HOLD, 0, b"").unwrap();
            let waited = t.elapsed();
            assert_eq!(r.payload, b"released");
            waited
        })
    });
    // Each idle call sleeps 1 ms, so there are never more of them than
    // milliseconds waited.
    let calls = w.idle_calls.load(Ordering::Relaxed);
    assert!(
        calls >= 3 && u128::from(calls) <= waited.as_millis(),
        "a {waited:?} wait in 1 ms sleeps is at most one idle call a millisecond, not {calls}"
    );
    assert!(
        w.least_waited_us.load(Ordering::Relaxed) as u128 >= CLIENT_SPIN_BUDGET.as_micros(),
        "idle_client was called before the spin budget was spent"
    );
}

/// A full ring is waited for the way a response is — spin, then idle — and
/// for no longer than the deadline. It used to be fifty million bare passes:
/// seconds of a core at full tilt.
#[test]
fn a_full_ring_is_waited_for_off_the_processor_and_not_for_ever() {
    let fx = Fixture::new(2);
    // Every slot claimed by requests that never complete; nobody serves.
    assert!(ring::claim_free(fx.seg(), &fx.geom).is_some());
    assert!(ring::claim_free(fx.seg(), &fx.geom).is_some());
    let w = Watching {
        idle_calls: AtomicU32::new(0),
        least_waited_us: AtomicU32::new(u32::MAX),
    };
    let c = RingClient::with_geom(fx.seg(), fx.geom, &w).with_deadline(Duration::from_millis(30));
    assert_eq!(c.submit(OP_ECHO, 0, b"x").err(), Some(IpcError::RingFull));
    let batch = c.submit_many_held(&[(OP_ECHO, 0, b"x".to_vec())]);
    assert_eq!(batch.err(), Some(IpcError::RingFull));
    assert!(
        w.idle_calls.load(Ordering::Relaxed) > 0,
        "the wait for a slot never left the spin"
    );
    assert!(w.least_waited_us.load(Ordering::Relaxed) as u128 >= CLIENT_SPIN_BUDGET.as_micros());
}

/// One deadline for a whole batch. Two requests served one after another, 30
/// and 35 ms of the client's clock, against a 60 ms deadline, and a third the
/// server holds. With a clock per request, started when its turn to be awaited
/// came, each of the first two was "on time" and the third would be waited for
/// from 65 ms: the call ran past its deadline without noticing. With one, the
/// deadline has passed by the time the third is awaited.
#[test]
fn a_batch_has_one_deadline_not_one_per_request() {
    let fx = Fixture::new(4);
    fx.with_workers(1, || {
        let c = RingClient::with_geom(fx.seg(), fx.geom, NapClock(&fx, Instant::now()))
            .with_deadline(Duration::from_millis(60));
        let done = AtomicBool::new(false);
        let r = thread::scope(|s| {
            // A client that never times out would wait on the held request for
            // ever: let it go after a while, so that shows as a failure.
            s.spawn(|| {
                let t = Instant::now();
                while !done.load(Ordering::SeqCst) && t.elapsed() < GIVE_UP {
                    thread::sleep(Duration::from_millis(1));
                }
                fx.open_latch();
            });
            let r = c.submit_many_held(&[
                (OP_NAP, 0, 30u64.to_le_bytes().to_vec()),
                (OP_NAP, 0, 35u64.to_le_bytes().to_vec()),
                (OP_HOLD, 0, Vec::new()),
            ]);
            done.store(true, Ordering::SeqCst);
            r
        });
        assert_eq!(
            r.err(),
            Some(IpcError::Timeout),
            "the batch's one deadline passed at 60 ms"
        );
        fx.until("every slot came back", || fx.slots_in(ST_FREE) == 4);
    });
}

/// Slots are reused: far more requests than slots, from more threads than
/// slots, with a server thread count below both. Nothing is lost, nothing is
/// answered with another request's bytes, and the ring ends empty.
#[test]
fn slots_are_reused_across_threads_without_crosstalk() {
    let fx = Fixture::new(4);
    fx.with_workers(2, || {
        thread::scope(|s| {
            for t in 0..8u32 {
                let fx = &fx;
                s.spawn(move || {
                    let c = fx.client();
                    for i in 0..3_000u32 {
                        let payload = [t.to_le_bytes(), i.to_le_bytes()].concat();
                        let r = c.submit(OP_ECHO, 0, &payload).unwrap();
                        assert_eq!(r.status, P::ST_OK);
                        assert_eq!(r.payload, payload, "thread {t} request {i}");
                    }
                });
            }
        });
    });
    for slot in 0..4 {
        assert_eq!(fx.state(slot), ST_FREE);
    }
}
