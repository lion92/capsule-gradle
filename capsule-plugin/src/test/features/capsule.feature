Feature: Capsule video generation from a reveal.js deck
  As an instructor
  I want to generate a video capsule from a reveal.js deck with synchronized TTS audio
  So that I can produce WebM pedagogical capsules without post-production

  Background:
    Given a Gradle project with the capsule plugin applied

  Scenario: Generate a WebM capsule from a deck with capsule script
    Given a reveal.js deck "mon-cours-deck.html" with 2 slides and data-capsule-slide attributes
    And a capsule script "mon-cours-script.txt" with 2 slide segments
    When I run the task "generateCapsuleVideo" with NoOp capture
    Then a video file "mon-cours.webm" is generated
    And the video file is not empty

  Scenario: NoOp fallback when Chromium is unavailable
    Given a reveal.js deck "test-deck.html" with 1 slides and data-capsule-slide attributes
    And a capsule script "test-script.txt" with 1 slide segments
    When I run the task "generateCapsuleVideo" with NoOp capture
    Then the task completes without error
    And a placeholder video is generated

  Scenario: Sequential fallback for deck without data-capsule-slide attributes
    Given a reveal.js deck "seq-deck.html" with 3 slides without data-capsule-slide attributes
    And a capsule script "seq-script.txt" with 3 sequentially ordered slide segments
    When I run the task "generateCapsuleVideo" with NoOp capture
    Then a video file "seq.webm" is generated
    And the injected deck HTML contains audio attributes for all slides

  Scenario: Multi-deck build produces separate videos
    Given a reveal.js deck "deck-a-deck.html" with 1 slides and data-capsule-slide attributes
    And a reveal.js deck "deck-b-deck.html" with 1 slides and data-capsule-slide attributes
    And a capsule script "deck-a-script.txt" with 1 slide segments
    And a capsule script "deck-b-script.txt" with 1 slide segments
    When I run the task "generateCapsuleVideo" with NoOp capture
    Then a video file "deck-a.webm" is generated
    And a video file "deck-b.webm" is generated

  Scenario: Audio injection into deck HTML
    Given a reveal.js deck "audio-deck.html" with 2 slides and data-capsule-slide attributes
    And a capsule script "audio-script.txt" with 2 slide segments
    When I run the task "generateCapsuleVideo" with NoOp capture
    Then the injected deck HTML contains "data-audio" attributes
    And the injected deck contains the "CAPSULE-GRADLE" autoplay script

  Scenario: Composite context JSON is parsed for engine N3 contract
    Given a reveal.js deck "ctx-deck.html" with 2 slides and data-capsule-slide attributes
    And a capsule script "ctx-script.txt" with 2 slide segments
    When I run the task "generateCapsuleVideo" with NoOp capture
    And I run the task "deployCapsule"
    And I run the task "collectCapsuleContext"
    And I run the task "transformCapsuleContext"
    Then the parsed output contains "deckName"
    And the parsed output contains "originalVideo"
    And the parsed output is a valid JSON array

  Scenario: Audio constraint — real TTS must produce binary MP3 not text
    Given a Gradle project with the capsule plugin configured for espeak TTS
    And a reveal.js deck "audio-real-deck.html" with 1 slides and data-capsule-slide attributes
    And a capsule script "audio-real-script.txt" with 1 slide segments
    When I run the task "generateCapsule" with espeak TTS
    Then the generated MP3 files must be binary audio not text placeholder

  Scenario: Output constraint — capsule video must be written to build/capsules/ directory
    Given a Gradle project with the capsule plugin configured for noop TTS
    And a reveal.js deck "out-deck.html" with 1 slides and data-capsule-slide attributes
    And a capsule script "out-script.txt" with 1 slide segments
    When I run the task "generateCapsuleVideo" with NoOp capture
    Then the video file "out.webm" exists in the build "capsules" directory

  Scenario: WebM validity constraint — generated video must have EBML header
    Given a Gradle project with the capsule plugin configured for noop TTS
    And a reveal.js deck "webm-deck.html" with 1 slides and data-capsule-slide attributes
    And a capsule script "webm-script.txt" with 1 slide segments
    When I run the task "generateCapsuleVideo" with NoOp capture
    Then the video file "webm.webm" has a valid WebM EBML header
