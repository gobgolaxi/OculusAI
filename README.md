# YuppyAI

One check: how a player's aim moves while they fight, judged by a small neural
network trained on **your own** players.

There is no bundled model and there cannot be a useful one. Aim looks different
on every server — ping, client versions, PvP style — so the plugin ships with the
machinery to collect data, label it, train on it and read the result, and the
data has to come from you.

## Parts

| Where | What |
|---|---|
| `src/` | Paper plugin: captures aim, extracts features, shows readings |
| `api/` | Python service: owns the dataset and the model |

The model lives outside the server on purpose. Training belongs where the
tooling for it is, the server should not load a machine learning stack to answer
one question, and the service can be retrained and restarted without touching
the game.

## Running it

**Service** — from `api/`:

```
pip install -r requirements.txt
python main.py
```

`YUPPYAI_HOST` and `YUPPYAI_PORT` override the defaults. `uvicorn main:app` works
too if you want reload or workers.

If `python` on your PATH is the Windows Store stub that just prints `Python` and
exits, call the real interpreter instead — the one `pip` installed into:

```
& "$env:LOCALAPPDATA\Python\pythoncore-3.14-64\python.exe" main.py
```

**Plugin** — needs [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/). Build
with `gradlew build`, or run a test server with everything downloaded for you:

```
gradlew runServer
```

Point `api.url` in `config.yml` at the service.

## The site, accounts, and plans

The service serves a public page at `/`: what YuppyAI does, and the plans.

A visitor **registers with an email and a password**, and the free plan is
issued on the spot — an account with no key cannot try the thing it signed up
for, so waiting for a human there would be pointless. Ordering a paid plan
records an order and nothing more; the operator confirms it on the Accounts tab
of the developer dashboard, and confirming is what issues the key. There is no
payment provider wired in: the service only records the operator's decision.

Signing in is by account, never by key. A key authenticates the *plugin*; an
account authenticates a *person*. Every per-key action on the panel re-checks
that the key belongs to the signed-in account, because holding a session says
who you are, not which keys you may touch.

### Plans

| Plan | Price | Players | Predicts/min | AutoMod | Pin model |
|---|---|---|---|---|---|
| Free | free | 10 | 300 | — | — |
| Starter | 299 RUB/mo | 40 | 1200 | yes | — |
| Pro | 799 RUB/mo | 150 | 4500 | yes | yes |
| Unlimited | 1999 RUB/mo | unlimited | unlimited | yes | yes |

### Subscriptions

One purchase buys 30 days. An account holds exactly one key and its string
never changes — an upgrade that handed out a new key would silently stop the
customer's anti-cheat until somebody edited `config.yml` and restarted. What
changes is the plan on the key, and when that plan runs out:

- **The same plan again** adds days. Buying twice in a row is a longer
  subscription, counted from the end of the current period rather than today,
  so buying early does not throw away the remainder.
- **A better plan** starts immediately, and whatever was left of the cheaper
  one is queued behind it — paying for an upgrade never burns time already paid
  for.
- **A cheaper plan** does not take effect now. It is queued and begins the
  moment the current period ends, because dropping somebody to a tier they have
  already paid past would be taking away time they own. Ordering the same
  cheaper plan again just makes the queued period longer.

When a period ends the key moves to whatever is queued, or falls back to Free
if nothing is. None of this runs on a timer: the rollover is applied whenever
anybody reads the key, which is the only moment it can matter.

The operator sets the days. Confirming an order on the Accounts tab takes a
day count — 30 by default, 365 for somebody who paid for a year at once. On the
Keys tab each key has an **Add days** field that shifts the current period by a
signed number, so `-7` takes a week off; cutting it below today simply ends the
period and the key rolls onto whatever is queued. Where something is queued,
**Queue days** changes how long it will run and **Drop queue** removes it.

Setting a plan by hand on the Keys tab overrules all of it — the period and the
queue are cleared with it, so the key cannot silently jump back later.

**No plan grants dataset access.** Pushing samples and running training shapes
the model everyone else is scored by, so it is not something a customer can
buy: keys issued by the order flow always have it off, and the operator turns
it on for a specific key by hand on the Keys tab.

Tiers are sold by player count and enforced as a predict rate: a player in
combat pushes roughly 30 windows a minute, so a tier sized for N players gets
`N * 30` predicts a minute. Over that the service answers **429** and the plugin
backs off, logging at most once a minute. Edit `yaiapi/plans.py` to change any
of this — the site, the API and the dashboard all read it from there.

## Developer dashboard

`http://127.0.0.1:8000/dashboard/dev` — the operator side: datasets, training,
model versions, key issuing, order confirmation and runtime controls.

The admin account has no password baked into the source. Set `YUPPYAI_ADMIN_USER`
and `YUPPYAI_ADMIN_PASSWORD` before the first start, or let the service invent one:
it prints that password to the console once and never stores it in the clear.
Change it in the dashboard before the port is reachable from anywhere else —
failed password attempts are limited to five per address per five minutes.

Operator endpoints (`/runtime`, `/models`, `/automod/status`, session and key
management) accept **only** a dashboard session or the `YUPPYAI_KEY` master key
from the box's own environment. A customer key is never enough, however valid.

## API keys

Keys are issued to accounts by the order flow above, and by hand on the
dashboard's Keys tab. A key carries a plan, an expiry and a list of addresses it
may be used from, because the realistic failure is not somebody guessing the
string — it is the string leaking in a config posted for help, or a screenshot.

Put the key in the plugin's `api.key`. Dataset endpoints (`/samples`, `/train`)
need a key the operator has granted dataset access to, set as `api.dataset-key`.

Refusals are numbered so a server owner can tell them apart at a glance:

| Code | Meaning |
|---|---|
| `001` | the request came from an address the key does not allow |
| `002` | the key has expired |
| `003` | no such key |
| `004` | no key was sent |
| `005` | predicts are turned off for this key |
| `006` | the key has no dataset access (granted per key by the operator) |
| `007` | operator credentials required (dashboard session or `YUPPYAI_KEY`) |
| `008` | over the plan's predict rate — answered as HTTP 429, retry shortly |

Nothing is open by default: with no keys issued, nothing can call the API.
`YUPPYAI_KEY` in the environment is a master key that passes every check,
including the operator ones, and is meant for scripts on the same box.
`YUPPYAI_OPEN=1` disables key checks entirely and exists only for a throwaway
local instance — the startup banner warns when it is on.

## Collecting data

A run has a cast. Sign the players up with the label they are being recorded
under, start everybody at once, and stop when the session is over — the captures
go to the service the moment you stop.

```
/yai data add cheater Bob      sign Bob up as cheater
/yai data add legit Ann        and Ann as legit
/yai data train                start recording everyone signed up
    ... they fight for a few minutes ...
/yai data stop                 stops and sends every capture straight away
```

`data train` starts a *recording*. Training the model is a separate thing and
lives in the dashboard — nothing trains automatically.

Other bits:

```
/yai data list                     who is signed up and how much they have
/yai data rem Bob                  take somebody off the roster
/yai data delete cheater Bob       drop what is already stored for them
```

Somebody added mid-run joins it immediately, and somebody who logs out mid-run
has their capture saved rather than dropped.

A window is only captured while a fight is happening — aim with nothing to aim
at carries no evidence either way, and feeding it in would teach the model that
idle mouse movement is what innocence looks like.

Every commit writes **its own file** under `api/data/sessions/`, named
`<date>-<time>-<label>-<player>.csv`, with an index in `sessions.json`. Appending
into one file per label makes a bad session permanent — the only way to undo it
is to edit a CSV by hand and hope you cut the right rows.

`/yai dashboard` → **Datasets** lists them. A click excludes a session from
training and another puts it back; shift-click deletes the file. Excluding is
the everyday action, which is why deleting takes the deliberate second gesture.

## Training and reading

`/yai dashboard` shows dataset counts, model accuracy and a Train button.
Accuracy is measured on a quarter of the data held back from training, so it
means something.

`/yai prob <player>` puts floating text over them and a live action bar on you:
current probability, the buffer, and the buffer's recent history. `/yai prob off`
stops.

`/yai forget <player>` drops the live suspicion — buffer to zero, history gone,
any evidence window called off — and starts watching them again from nothing.
It does not touch data already in the dataset; that is `/yai data delete`.

## The buffer

A single window is noisy — a human can flick like a robot once. The buffer rises
by how far a reading sits above `buffer.suspicion` and falls when it is below,
and it drains faster than it fills, so clearing yourself is easier than being
convicted. Alerts fire on the buffer, never on one window.

## Honest limits

- **The model is only as good as the labels.** Windows labelled `legit` that came
  from someone quietly cheating will teach it that cheating is normal, and no
  amount of training fixes that.
- **A lopsided dataset scores well and detects nothing** — a model fed nine parts
  legit learns to always answer legit and is right 90% of the time. The dashboard
  warns when the ratio passes 3:1.
- **This is evidence, not proof.** It says the aim in a window resembles the aim
  in windows you labelled as cheating. That is worth a look from a human, and it
  is not worth an automatic ban.
- **It kicks by default.** `punishment.enabled` ships as `true` with a kick
  command. That rests entirely on your labels being right — an untrained or
  badly labelled model will kick honest players with the same confidence. Set it
  to `false` while you are collecting the first dataset.

## What happens on a detection

Crossing the buffer threshold opens a case rather than ending one:

1. The player keeps being recorded for `punishment.evidence.seconds` (three
   minutes by default).
2. That capture is filed as a labelled session, so the run that caught somebody
   becomes training data.
3. Only then is the command run — and if the buffer has drained below
   `cancel-below` by that point, nothing happens at all.

The waiting does two jobs. It turns every detection into data, and it is a
second opinion: a confident moment that was a fluke fades during those minutes
and calls itself off. The cost is a few more minutes of a cheat that was already
running.

Both switches are in `/yai dashboard` as well as in the config, so acting on
detections and the evidence window can each be turned off from in game.

## License

The plugin is [MIT licensed](LICENSE): fork it, change it, ship it, sell it —
the only condition is that the copyright notice travels with the copies. The
service in `yaiapi/`, the trained model and the dataset are not part of this
repository and are not covered by it.
