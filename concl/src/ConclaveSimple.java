import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;

public class ConclaveSimple {
    private static final int CARDINALS = 135;
    private static final int NEEDED = 90;
    private static final double CHAT_SWING_BASE = 0.6;
    private static final double CHAT_SWING_SPAN = 0.8;
    private static final double POWER_EPSILON = 0.0001;
    private static final double TALK_CONVERT_FACTOR = 0.5;
    private static final int ABBREV_LOG_LIMIT = 40;

    public static void main(String[] args) {
        long seed = System.currentTimeMillis();
        String logFile = "conclave-log.txt";
        boolean fullLog = true;

        if (args.length >= 1) {
            seed = parseSeedWithFallback(args[0], seed);
        }
        if (args.length >= 2) {
            logFile = args[1];
        }
        if (args.length >= 3) {
            fullLog = parseBooleanWithFallback(args[2], true);
        }

        Ledger log = null;
        try {
            log = new Ledger(logFile, fullLog);
            CuriaEngine engine = new CuriaEngine(seed, log);
            engine.runElection();
        } catch (IOException ioe) {
            System.out.println("Cannot open log file: " + logFile);
            System.out.println(ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            System.out.println("Simulation interrupted");
        } finally {
            if (log != null) {
                log.close();
            }
        }
    }

    private static long parseSeedWithFallback(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException nfe) {
            System.out.println("Seed '" + raw + "' is invalid, falling back to current-time seed.");
            return fallback;
        }
    }

    private static boolean parseBooleanWithFallback(String raw, boolean fallback) {
        if (raw.equals("true") || raw.equals("yes") || raw.equals("y") || raw.equals("1")) {
            return true;
        }
        if (raw.equals("false") || raw.equals("no") || raw.equals("n") || raw.equals("0")) {
            return false;
        }

        System.out.println("fullLog value '" + raw + "' not recognized, using default: " + fallback);
        return fallback;
    }

    private static final class CuriaEngine {
        private final CardinalThread[] college = new CardinalThread[CARDINALS + 1];
        private final DiscussionRoom discussionRoom = new DiscussionRoom(CARDINALS);
        private final DiscussionOrder order = new DiscussionOrder(CARDINALS);
        private final BallotBox ballotBox;
        private final Ledger log;

        CuriaEngine(long seed, Ledger log) {
            this.log = log;
            this.ballotBox = new BallotBox(CARDINALS, NEEDED, log);

            Random seedMaker = new Random(seed);
            for (int id = 1; id <= CARDINALS; id++) {
                Random cardRnd = new Random(seedMaker.nextLong());
                double influence = 0.4 + cardRnd.nextDouble() * 1.6;
                double openness = 0.1 + cardRnd.nextDouble() * 0.8;
                int pref = 1 + cardRnd.nextInt(CARDINALS);
                Random socialRnd = new Random(seedMaker.nextLong());
                college[id] = new CardinalThread(id, influence, openness, pref, socialRnd, college,
                        discussionRoom, order, ballotBox, log);
            }
        }

        void runElection() throws InterruptedException {
            log.log("Conclave starts with " + CARDINALS + " cardinals.");
            for (int id = 1; id <= CARDINALS; id++) {
                college[id].start();
            }

            RoundResult finalRound = ballotBox.awaitElection();

            for (int id = 1; id <= CARDINALS; id++) {
                college[id].interrupt();
            }
            for (int id = 1; id <= CARDINALS; id++) {
                college[id].join();
            }

            log.log("Habemus Papam!");
            log.log("After round " + finalRound.round + ", Cardinal " + finalRound.leader + " is elected with "
                    + finalRound.winningVotes + " votes.");
        }
    }

    private static final class CardinalThread extends Thread {
        private final int id;
        private final double influence;
        private final double openness;
        private volatile int preference;
        private final Random rnd;

        private final CardinalThread[] all;
        private final DiscussionRoom room;
        private final DiscussionOrder order;
        private final BallotBox ballot;
        private final Ledger log;

        CardinalThread(int id, double influence, double openness, int preference, Random rnd,
                CardinalThread[] all, DiscussionRoom room, DiscussionOrder order, BallotBox ballot, Ledger log) {
            super("Cardinal-" + id);
            this.id = id;
            this.influence = influence;
            this.openness = openness;
            this.preference = preference;
            this.rnd = rnd;
            this.all = all;
            this.room = room;
            this.order = order;
            this.ballot = ballot;
            this.log = log;
        }

        @Override
        public void run() {
            int round = 1;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // Keep discussions in a fixed order so a seed fully reproduces a run.
                    for (int convo = 0; convo < 2; convo++) {
                        order.awaitTurn(round, convo, id);
                        haveConversation(round);
                        order.doneTurn(convo);
                    }

                    room.waitForEveryone();

                    RoundResult result = ballot.castVoteAndWait(id, getPreference());
                    if (result.elected) {
                        break;
                    }

                    reactToResult(result);
                    round++;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private void haveConversation(int round) {
            int hallBuddyId = pickPeer();
            CardinalThread hallBuddy = all[hallBuddyId];

            int myTicket = getPreference();
            int buddyTicket = hallBuddy.getPreference();

            double myPower = influence * (CHAT_SWING_BASE + CHAT_SWING_SPAN * rnd.nextDouble());
            double buddyPower = hallBuddy.influence * (CHAT_SWING_BASE + CHAT_SWING_SPAN * rnd.nextDouble());
            double buddyWinsChance = buddyPower / (myPower + buddyPower + POWER_EPSILON);

            boolean buddyWins = rnd.nextDouble() < buddyWinsChance;
            if (buddyWins) {
                if (buddyTicket != myTicket) {
                    double switchChance = openness * TALK_CONVERT_FACTOR;
                    if (rnd.nextDouble() < switchChance) {
                        int old = setPreference(buddyTicket);
                        if (old != buddyTicket) {
                            log.logPersuasion(round,
                                    "Cardinal " + hallBuddyId + " persuades Cardinal " + id + " (" + old + " -> "
                                            + buddyTicket + ")");
                        }
                    }
                }
                return;
            }

            if (buddyTicket != myTicket) {
                double switchChance = hallBuddy.openness * TALK_CONVERT_FACTOR;
                if (rnd.nextDouble() < switchChance) {
                    int old = hallBuddy.setPreference(myTicket);
                    if (old != myTicket) {
                        log.logPersuasion(round,
                                "Cardinal " + id + " persuades Cardinal " + hallBuddyId + " (" + old + " -> "
                                        + myTicket + ")");
                    }
                }
            }
        }

        private int pickPeer() {
            int mateId = id;
            while (mateId == id) {
                mateId = 1 + rnd.nextInt(CARDINALS);
            }
            return mateId;
        }

        private void reactToResult(RoundResult result) {
            int myTicket = getPreference();

            double followLeader = 0.10 + openness * 0.15;
            if (myTicket != result.leader && rnd.nextDouble() < followLeader) {
                setPreference(result.leader);
                return;
            }

            double buildCompetition = 0.05 + openness * 0.10;
            if (myTicket == result.leader && result.second != result.leader && rnd.nextDouble() < buildCompetition) {
                setPreference(result.second);
                return;
            }

            double sampleTopThree = 0.03 + openness * 0.08;
            if (rnd.nextDouble() < sampleTopThree) {
                int pick = rnd.nextInt(3);
                if (pick == 0) {
                    setPreference(result.leader);
                } else if (pick == 1) {
                    setPreference(result.second);
                } else {
                    setPreference(result.third);
                }
            }
        }

        synchronized int getPreference() {
            return preference;
        }

        synchronized int setPreference(int pref) {
            int old = preference;
            preference = pref;
            return old;
        }
    }

    private static final class DiscussionOrder {
        private final int n;
        private int round = 1;
        private int convo = 0;
        private int turn = 1;

        DiscussionOrder(int n) {
            this.n = n;
        }

        synchronized void awaitTurn(int expectedRound, int expectedConvo, int cardinalId) throws InterruptedException {
            while (round != expectedRound || convo != expectedConvo || turn != cardinalId) {
                wait();
            }
        }

        synchronized void doneTurn(int finishedConvo) {
            if (finishedConvo != convo) {
                // should never happen unless there is a bug in the turn logic
                System.out.println("ERROR: convo sequencing mismatch, something is very wrong");
                return;
            }

            if (turn < n) {
                turn++;
                notifyAll();
                return;
            }

            turn = 1;
            if (convo == 0) {
                convo = 1;
            } else {
                convo = 0;
                round++;
            }
            notifyAll();
        }
    }

    private static final class DiscussionRoom {
        private final int size;
        private int arrived;
        private int released;

        DiscussionRoom(int size) {
            this.size = size;
        }

        synchronized void waitForEveryone() throws InterruptedException {
            arrived++;
            if (arrived == size) {
                released = size;
                notifyAll();
            } else {
                while (arrived < size) {
                    wait();
                }
            }

            released--;
            if (released == 0) {
                arrived = 0;
                notifyAll();
            } else {
                while (released > 0) {
                    wait();
                }
            }
        }
    }

    private static final class BallotBox {
        private final int seats;
        private final int needed;
        private final Ledger log;
        private final int[] submittedVotes;

        private int submitted;
        private int seen;
        private boolean closed;
        private int round = 1;
        private int lastLeader = -1;
        private boolean elected;
        private RoundResult result;

        BallotBox(int seats, int needed, Ledger log) {
            this.seats = seats;
            this.needed = needed;
            this.log = log;
            this.submittedVotes = new int[seats + 1];
        }

        synchronized RoundResult castVoteAndWait(int voterId, int candidateId) throws InterruptedException {
            while (closed) {
                wait();
            }

            submittedVotes[voterId] = candidateId;
            submitted++;

            if (submitted == seats) {
                result = countRound();
                closed = true;
                notifyAll();
            } else {
                while (!closed) {
                    wait();
                }
            }

            RoundResult myResult = result;
            seen++;
            if (seen == seats) {
                // Last reader resets the ballot for the next round.
                seen = 0;
                submitted = 0;
                closed = false;
                for (int i = 0; i < submittedVotes.length; i++) {
                    submittedVotes[i] = 0;
                }
                if (!elected) {
                    round++;
                }
                notifyAll();
            } else {
                while (closed) {
                    wait();
                }
            }

            return myResult;
        }

        synchronized RoundResult awaitElection() throws InterruptedException {
            while (!elected) {
                wait();
            }
            return result;
        }

        private RoundResult countRound() {
            int[] scrutinyLedger = new int[seats + 1];
            for (int voter = 1; voter <= seats; voter++) {
                int pick = submittedVotes[voter];
                if (pick >= 1 && pick <= seats) {
                    scrutinyLedger[pick]++;
                }
            }

            int papabile1 = 1;
            int papabile2 = 1;
            int papabile3 = 1;
            int votes1 = -1;
            int votes2 = -1;
            int votes3 = -1;

            // Curia convention for ties: first seat discovered in scan order keeps priority.
            for (int cand = 1; cand <= seats; cand++) {
                int v = scrutinyLedger[cand];
                if (v > votes1) {
                    papabile3 = papabile2;
                    votes3 = votes2;
                    papabile2 = papabile1;
                    votes2 = votes1;
                    papabile1 = cand;
                    votes1 = v;
                } else if (v > votes2) {
                    papabile3 = papabile2;
                    votes3 = votes2;
                    papabile2 = cand;
                    votes2 = v;
                } else if (v > votes3) {
                    papabile3 = cand;
                    votes3 = v;
                }
            }

            boolean leaderChanged = lastLeader != -1 && lastLeader != papabile1;
            String line = "Round " + round + ": leader C" + papabile1 + " with " + votes1 + " votes. Top 3: #1 C"
                    + papabile1 + " (" + votes1 + ") | #2 C" + papabile2 + " (" + votes2 + ") | #3 C" + papabile3
                    + " (" + votes3 + ")";
            if (leaderChanged) {
                line = line + " Leader changed: C" + lastLeader + " -> C" + papabile1;
            }
            log.log(line);

            lastLeader = papabile1;
            elected = votes1 >= needed;
            RoundResult rr = new RoundResult(round, elected, papabile1, votes1, papabile2, papabile3);
            if (elected) {
                notifyAll();
            }
            return rr;
        }
    }

    private static final class RoundResult {
        final int round;
        final boolean elected;
        final int leader;
        final int winningVotes;
        final int second;
        final int third;

        RoundResult(int round, boolean elected, int leader, int winningVotes, int second, int third) {
            this.round = round;
            this.elected = elected;
            this.leader = leader;
            this.winningVotes = winningVotes;
            this.second = second;
            this.third = third;
        }
    }

    private static final class Ledger {
        private final PrintWriter out;
        private final boolean full;
        private int trackedRound = -1;
        private int discussionEvents = 0;
        private boolean omitted;

        Ledger(String file, boolean full) throws IOException {
            this.full = full;
            this.out = new PrintWriter(new BufferedWriter(new FileWriter(file, false)));
        }

        synchronized void logPersuasion(int round, String line) {
            if (full) {
                log(line);
                return;
            }

            if (trackedRound != round) {
                trackedRound = round;
                discussionEvents = 0;
                omitted = false;
            }

            if (discussionEvents < ABBREV_LOG_LIMIT) {
                discussionEvents++;
                log(line);
                return;
            }

            if (!omitted) {
                omitted = true;
                log("Round " + round + " discussion: more conversations omitted...");
            }
        }

        synchronized void log(String line) {
            System.out.println(line);
            out.println(line);
            out.flush();
        }

        public synchronized void close() {
            out.flush();
            out.close();
        }
    }
}
