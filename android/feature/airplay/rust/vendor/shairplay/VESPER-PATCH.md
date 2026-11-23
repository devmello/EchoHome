# Vesper patch to shairplay 0.13.0

Copied from crates.io (shairplay 0.13.0, LGPL-3.0-or-later), one change:

- `src/raop/connection.rs`, `socket_ip_bytes`: `address.ip().to_canonical()`, so an IPv4 client
  on a dual-stack `[::]` listener (seen as `::ffff:a.b.c.d`) is handled as IPv4. Without it the
  AP1 `Apple-Response` would sign 16 address bytes and iOS would reject it.

Why: Vesper binds one `[::]` listener (Android has `bindv6only=0`, so the library's default
`0.0.0.0` + `[::]` pair fails on the second bind and leaves IPv4 only; iPhones connect over IPv6).
