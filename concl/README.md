# White Paper: Multi-Threaded Conclave Simulation

## 1. Objective

The goal was to model a conclave in which 135 cardinals repeatedly vote until one candidate reaches a two-thirds majority.
The implementation emphasizes thread concurrency, decentralized behavior, and synchronization correctness.

## 2. Design Overview

### 2.1 Cardinal as Autonomous Thread

Each cardinal is implemented as an independent thread (`Cardinal extends Thread`).
A cardinal keeps its own internal voting preference and updates it according to:

- Social influence from peers during the discussion phase.
- Strategic alignment toward the leading candidate after each round.

This reflects varying persuasion strength and susceptibility to opinion changes.

### 2.2 Shared Components

Two synchronized shared components coordinate collective behavior:

- `DiscussionRoom`: a phase barrier ensuring all cardinals finish discussion before voting.
- `BallotBox`: a synchronized vote collector that computes round outcomes and decides whether election conditions are met.

No single thread dictates how cardinals must vote; each cardinal updates state independently.

## 3. Synchronization Strategy

### 3.1 Discussion Barrier

`DiscussionRoom.awaitDiscussionCompletion()` uses `synchronized`, `wait`, and `notifyAll` to implement a reusable barrier.
All cardinals must arrive before any can continue to voting.

### 3.2 Voting Barrier and Round Control

`BallotBox.submitVoteAndAwaitRoundResult(...)` performs three duties atomically:

1. Accept one vote from each cardinal.
2. Evaluate the round once all votes arrive.
3. Release all waiting cardinals with a consistent result.

A second acknowledgment counter prevents early transition to the next round before all cardinals have observed the result.

### 3.3 Conclave Termination

When a candidate reaches the threshold, the `BallotBox` marks the election as concluded.
The main thread receives the final result and interrupts all cardinal threads for clean shutdown.

## 4. Majority Rule

Required threshold is computed as:

`ceil(2N/3)`

For `N = 135`, the threshold is `90` votes.

## 5. Behavioral Model

Each discussion phase samples several peer interactions.
A cardinal may switch preference based on peer influence and personal openness.
After voting, a cardinal may strategically move toward the leading candidate.
This creates realistic convergence dynamics while preserving randomness.

## 6. Correctness and Safety Notes

- Shared mutable state is only modified inside synchronized methods or through `volatile` reads/writes for preferences.
- Thread coordination avoids busy waiting.
- Interruption handling ensures deterministic stop behavior once a Pope is elected.

## 7. Extensions

Possible improvements include:

- Ideological blocks (factions) and coalition behavior.
- Candidate withdrawal after repeated poor performance.
- Logging full tallies to CSV for statistical analysis over many simulation runs.
