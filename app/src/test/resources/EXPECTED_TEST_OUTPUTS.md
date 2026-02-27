# NoteNotes — Expected Test Outputs for Real Guitar Audio

These test audio files are real guitar recordings made on a Samsung Galaxy S24.
Each file was recorded with the NoteNotes app's microphone input.

## Single Note Tests

Each single note test consists of the open string plucked **once** and allowed
to ring out / decay naturally. The transcription should produce exactly **1 note**
matching the expected pitch.

### low_e2.wav — Low E String (6th string)
- **Played**: Open 6th string, plucked once, let ring
- **Expected notes**: 1 note
- **Expected pitch**: E2 (MIDI 40, 82.41 Hz)
- **Expected note name**: E2

### a2.wav — A String (5th string)
- **Played**: Open 5th string, plucked once, let ring
- **Expected notes**: 1 note
- **Expected pitch**: A2 (MIDI 45, 110.00 Hz)
- **Expected note name**: A2

### d3.wav — D String (4th string)
- **Played**: Open 4th string, plucked once, let ring
- **Expected notes**: 1 note
- **Expected pitch**: D3 (MIDI 50, 146.83 Hz)
- **Expected note name**: D3

### g3.wav — G String (3rd string)
- **Played**: Open 3rd string, plucked once, let ring
- **Expected notes**: 1 note
- **Expected pitch**: G3 (MIDI 55, 196.00 Hz)
- **Expected note name**: G3

### b3.wav — B String (2nd string)
- **Played**: Open 2nd string, plucked once, let ring
- **Expected notes**: 1 note
- **Expected pitch**: B3 (MIDI 59, 246.94 Hz)
- **Expected note name**: B3

### e4.wav — High E String (1st string)
- **Played**: Open 1st string, plucked once, let ring
- **Expected notes**: 1 note
- **Expected pitch**: E4 (MIDI 64, 329.63 Hz)
- **Expected note name**: E4
- **Known issue**: Quiet signal (~36% max amplitude); YIN may detect
  subharmonic A2 (110 Hz = 329.6/3). Algorithm improvement needed.

## Chord Tests

### c_major.wav — C Major Chord
- **Played**: Open C major chord (x32010), strummed once, let ring
- **Expected notes**: 1 chord event
- **Expected pitches** (standard open C major voicing):
  - C3 (MIDI 48) — 5th string, 3rd fret
  - E3 (MIDI 52) — 4th string, 2nd fret
  - G3 (MIDI 55) — 3rd string, open
  - C4 (MIDI 60) — 2nd string, 1st fret
  - E4 (MIDI 64) — 1st string, open
- **Expected chord name**: C or Cmaj
- **Note**: Not all pitches may be detected; at minimum the chord should
  contain C, E, and G pitch classes.

## Sequence Tests

### open_strings.wav — All Open Strings in Sequence
- **Played**: Each open string plucked once, in order from low to high:
  E2 → A2 → D3 → G3 → B3 → E4
- **Expected notes**: 6 notes in sequence
- **Expected pitch sequence**:
  1. E2 (MIDI 40)
  2. A2 (MIDI 45)
  3. D3 (MIDI 50)
  4. G3 (MIDI 55)
  5. B3 (MIDI 59)
  6. E4 (MIDI 64)
- **Note**: Some notes may ring into each other (guitar sustain).
  The transcription should still produce 6 distinct note events
  in the correct pitch order.

## Test Pass Criteria

### Pitch Accuracy
- Single notes: detected pitch must match expected MIDI note exactly
  (within ±1 semitone is acceptable for difficult cases like E4)
- Chords: at minimum the correct pitch classes (C, E, G for C major)
  must be present in the detected chord

### Note Count
- Single notes: exactly 1 note detected (not 0, not 2+)
- Chords: exactly 1 chord event
- Open strings sequence: exactly 6 notes (±1 acceptable if adjacent
  strings bleed into each other)

### Note Order (sequence tests)
- Notes must appear in the correct pitch-ascending order for the
  open strings test

## Algorithm Performance Tracking

| File            | Expected       | YIN Result     | MPM Result     | HPS Result     | Consensus |
|-----------------|----------------|----------------|----------------|----------------|-----------|
| low_e2.wav      | 1× E2          | TBD            | TBD            | TBD            | TBD       |
| a2.wav          | 1× A2          | TBD            | TBD            | TBD            | TBD       |
| d3.wav          | 1× D3          | TBD            | TBD            | TBD            | TBD       |
| g3.wav          | 1× G3          | TBD            | TBD            | TBD            | TBD       |
| b3.wav          | 1× B3          | TBD            | TBD            | TBD            | TBD       |
| e4.wav          | 1× E4          | TBD            | TBD            | TBD            | TBD       |
| c_major.wav     | 1× C chord     | TBD            | TBD            | TBD            | TBD       |
| open_strings    | 6× E2→E4       | TBD            | TBD            | TBD            | TBD       |
