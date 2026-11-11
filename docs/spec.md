# Slashboard spec

Client-only Fabric mod for Minecraft 26.3. Press a key, pick a command from a grouped menu, fill its values in proper fields, run it. No server install.

## Agreed with Shazwan (11 Nov 2026)

- Runs on the client only (Onyx Prism instance `26.3`). Works on any server.
- Commands come from the command tree the server sends on join, so the menu only lists what you're allowed to run, modded ones included.
- Commands are sorted into groups: World, Player, Items, Teleport, Server, and Mods / other for anything not in the built-in map.
- Each command gets a form built from its tree:
  - a fixed word choice (`time set|add|query`) shows as toggle chips, or as a pick box with suggestions when there are more than 6 options (like `/gamerule`)
  - a value shows as a text box with a suggestion dropdown fed by the same path the chat box uses (online players, items, enchantments, modded suggestions from the server)
  - parts after a point where the command can already run are marked optional
  - redirects into the top of the tree (`/execute ... run`) fall back to one free text box
- Item boxes (`/give`, `/item ... with`) get a Customise button that opens an item builder: name (colour, bold, italic), lore lines, any number of enchantments (picked from the server's enchantment list, levels 1 to 255), Unbreakable, Glow, and for firework rockets flight 1 to 3 plus explosions (shape, colours, fade colours, trail, twinkle). It writes the `[...]` components into the item box. It only writes one way: hand edits to the components get replaced on the next builder change.
- Preview line shows the exact command before it runs. A parse problem is shown as a hint but never blocks Run, since the client can't always judge permission-gated arguments.
- Favourites: saved presets (name + full command, one click runs) and Recent (last 20 commands run through Slashboard, click to run, save as preset).
- Stored in `config/slashboard.json`. A broken file is renamed to `slashboard.json.bad`, never silently thrown away.

## Controls

- Default key `H`, picked because G is vanilla Quick Actions and J is Litematica on Onyx. Changeable in Options, Controls, Key Binds, under Slashboard. Minecraft won't warn about clashes with Litematica/Tweakeroo hotkeys, since those live in their own configs.
- Search box is focused on open. Typing filters all commands, Enter opens the first match.
- Enter in a form runs it. Run closes the menu, Shift + Run keeps it open. Esc closes.
- In a suggestion dropdown: Up/Down to move, Tab or Enter to accept.

## Motion (Emil Kowalski's rules)

- No open/close animation: it's keyboard-triggered and used many times a session.
- Hover and selection highlights are instant.
- Run and chips give press feedback (darker fill, 1px sink) while the mouse is down.
- "Saved" confirmation fades out with an ease-out curve over about 1.2s, the only timed motion.

## Pieces

| File | Job |
| --- | --- |
| `CommandForm` | Walks one command's Brigadier tree into form steps, builds the command string. Brigadier only, unit-tested. |
| `Groups` | Maps a root command name to its group. |
| `Store` | Loads and saves presets and recent history. |
| `SlashboardScreen` | The GUI. |
| `SlashboardClient` | Registers the key and opens the screen. |

## Out of scope

Server-side component, per-server preset lists, custom hand-made forms, theming options.
