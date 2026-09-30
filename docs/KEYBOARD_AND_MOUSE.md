# Keyboard and mouse

The Android phone/tablet application supports hardware keyboards and mouse input,
including desktop windows and ChromeOS. Wear keeps its existing input behavior.
Open **Keyboard shortcuts** from the workspace menu, or press F1 for help.

| Context | Keys | Action |
| --- | --- | --- |
| Lists | Up/Down or K/J | Select previous/next item without opening or marking read |
| Lists | Home/End | Select first/last item |
| Lists | Page Up/Down | Move selection roughly one viewport |
| Lists | Enter/Space | Open selected item |
| Reader content | Up/Down or K/J | Scroll |
| Reader content | Space/Shift+Space or Page Down/Up | Scroll one page |
| Reader content | Home/End | Beginning/end of article |
| Reader | Alt+Page Up/Down | Previous/next article in the originating list |
| Selected/open article | R | Toggle read/unread; marking unread in reader returns to the list |
| Workspace/reader | U or Ctrl+Z | Undo Mark Read while an Undo action remains available |
| Selected/open article | S | Save/remove from Saved |
| Selected/open article | O | Open in browser |
| Selected/open article | Ctrl+Shift+S | Share URL |
| Selected/open article | Ctrl+Shift+K | Send to Karakeep |
| Workspace | Ctrl+F or / | Search the current workspace |
| Workspace | Ctrl+R | Sync articles |
| Workspace | Ctrl+Shift+R | Refresh feeds |
| Workspace | Ctrl+1/2/3 | Inbox/Saved/Library |
| Workspace | Ctrl+, | Settings |
| General | Tab/Shift+Tab | Move between controls |
| General | Escape or Alt+Left | Dismiss the current popup/dialog or go back |
| General | F1, ? or Ctrl+? | Shortcut help |

R, U and Alt+Left retain Brooklet Linux's read/unread, Undo and Back meanings.
Text fields retain ordinary editing shortcuts; article shortcuts do not run while
editing. Focused buttons retain Enter/Space activation, and navigation controls
retain their arrow navigation. Help remains available while editing.

Mouse clicks select a headline; double-click opens it; right-click opens article
actions. Headlines show hover and selection feedback. Wheel/trackpad scrolling
uses the standard list scroller. Touch still opens with one tap and supports Inbox
swipe-to-read. Selection never marks an article read.

Selection uses article IDs, survives leading sync insertions and restores after
reader return. When a selected article disappears, the next surviving neighbor
is selected, falling back to the previous item at the end. Undo restores the
removed selection when the user has not moved on, and preserves the existing
viewport safeguards. If the user moves to another headline before Undo, that
selection stays in place. Each workspace
retains its navigation state. Held movement keys repeat; action keys run once per
press. Search remains a cached headline/feed/author search; reader-local find is
not included.

## Regression gate

Build the instrumentation APK and run the real-input journeys on an emulator or
test device before accepting changes to keyboard routing:

```sh
./gradlew :app-phone:testDebugUnitTest :app-phone:lintDebug \
  :app-phone:assembleDebug :app-phone:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5556 ./gradlew :app-phone:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nedrichards.brooklet.KeyboardJourneyTest
```

`KeyboardJourneyTest` exercises actual key/mouse events and Room mutations rather
than only testing a shortcut lookup table. Also run the existing reader, snackbar
and Undo journeys to protect touch and read-state behavior. Hardware keyboard,
mouse/trackpad and ChromeOS verification should be recorded separately from
emulator evidence.
