# Price Tracker (Fakepixel Skyblock mod)

A client-side Forge 1.8.9 mod that tracks Bazaar and Auction House prices,
shows them in item tooltips and an in-game GUI, and shares item prices with
a community server.

## What the mod sends to the server

Only item market data, taken from the menus you open:

- item name
- market (Bazaar or Auction House) and category
- price and sell price

## What it never sends

- your Minecraft username, UUID, chat, inventory or coins
- your in-game watchlist and price alerts (they stay on your PC)
- any other file or setting from your computer

The server is a normal website, so it can see your IP address when the mod
connects, like any website.

## Optional: link your website account

If you paste a link code into `config/fakepixel_pricetracker/sync.json`
(`modToken`), the mod downloads your website watchlist from the server.
This is off by default (`modToken` is empty). The token is like a password
for your watchlist, so never share your `sync.json`.

## Turn sharing off

In `config/fakepixel_pricetracker/sync.json`:

- `"upload": false` stops uploading your prices
- `"enabled": false` turns the whole sync off

The sync code is in `PriceSync.java`. It is short, so you can read it.

## Check that the jar matches this code

Every release lists a SHA-256 hash for its jar. On Windows:

    certutil -hashfile pricetracker-1.0.jar SHA256

If the result differs from the hash on the release page, do not use it.

## Build it yourself

1. Install a full JDK 8 (not only a JRE) and set JAVA_HOME to it.
2. Run `gradlew clean build`.
3. The jar appears in `build/libs`.

## License

Source-available, all rights reserved. You may read the code and check what
it does, but you may not copy, change or redistribute it. See
[LICENSE](LICENSE). The Forge and Gradle files in this repository keep their
own licenses (see the *License*, *Credits* and *Paulscode* files).

## Contact

[YOUR EMAIL OR DISCORD]
