# PractAid

## What

[PractAid](practaid.com) is a music practice tool that lets Spotify premium
users loop (or 'A-B loop') segments of Spotify songs.

## TODO
- [ ] Add link back to source
- [ ] Expound on functionality, permissions required
- [ ] Mention supported browsers
- [ ] Add support for Safari
- [ ] Re-design such that the main looper screen is shown even before logging in.
      The intro/description could be a modal.
- [ ] Add placeholders for album cover, waveform etc. pre-login
- [ ] Add help text for hotkeys
- [ ] More elegant way for starting a mocked-out offline dev build
- [ ] Finish pulling apart `practaid.events` ns
- [ ] Pull apart DB

## Mocking FX (Offline mode)

The most important thing to mock is the Player object, which needs to:
* include the path `[:track_window :current_track]`
* have a track with a duration
* periodically emit state-change events
* respond to commands such as seek, pause, etc.
* include internal state such as `:paused`
* go to a new track when the current track ends