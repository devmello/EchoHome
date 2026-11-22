//! AirPlay 2 pairing store kept in the app's private files, so the receiver's identity
//! (`pk` in the TXT records) is stable across restarts and iPhones don't have to re-pair.
//!
//! Files in `<state_dir>`: `identity` (32-byte seed), `peers` (`<device id> <64 hex>` per line).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

use shairplay::PairingStore;

pub struct FilePairingStore {
    dir: PathBuf,
    peers: Mutex<HashMap<String, [u8; 32]>>,
}

impl FilePairingStore {
    pub fn open(dir: &str) -> Self {
        let dir = PathBuf::from(dir);
        let _ = std::fs::create_dir_all(&dir);
        let peers = std::fs::read_to_string(dir.join("peers"))
            .map(|text| parse_peers(&text))
            .unwrap_or_default();
        Self { dir, peers: Mutex::new(peers) }
    }

    fn save_peers(&self, peers: &HashMap<String, [u8; 32]>) {
        let mut text = String::new();
        for (id, key) in peers {
            text.push_str(id);
            text.push(' ');
            text.push_str(&to_hex(key));
            text.push('\n');
        }
        let tmp = self.dir.join("peers.tmp");
        if std::fs::write(&tmp, text).is_ok() {
            let _ = std::fs::rename(&tmp, self.dir.join("peers"));
        }
    }
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn from_hex32(s: &str) -> Option<[u8; 32]> {
    if s.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, chunk) in s.as_bytes().chunks(2).enumerate() {
        out[i] = u8::from_str_radix(std::str::from_utf8(chunk).ok()?, 16).ok()?;
    }
    Some(out)
}

fn parse_peers(text: &str) -> HashMap<String, [u8; 32]> {
    text.lines()
        .filter_map(|line| {
            let (id, hex) = line.split_once(' ')?;
            Some((id.to_string(), from_hex32(hex.trim())?))
        })
        .collect()
}

impl PairingStore for FilePairingStore {
    fn get(&self, device_id: &str) -> Option<[u8; 32]> {
        self.peers.lock().ok()?.get(device_id).copied()
    }

    fn put(&self, device_id: &str, public_key: [u8; 32]) {
        if let Ok(mut peers) = self.peers.lock() {
            peers.insert(device_id.to_string(), public_key);
            self.save_peers(&peers);
        }
    }

    fn remove(&self, device_id: &str) {
        if let Ok(mut peers) = self.peers.lock() {
            peers.remove(device_id);
            self.save_peers(&peers);
        }
    }

    fn has_any_pairing(&self) -> bool {
        self.peers.lock().map(|p| !p.is_empty()).unwrap_or(false)
    }

    fn load_identity(&self) -> Option<[u8; 32]> {
        let bytes = std::fs::read(self.dir.join("identity")).ok()?;
        bytes.try_into().ok()
    }

    fn save_identity(&self, seed: [u8; 32]) {
        let _ = std::fs::write(self.dir.join("identity"), seed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn peers_round_trip() {
        let mut m = HashMap::new();
        m.insert("AA:BB".to_string(), [7u8; 32]);
        let text = format!("AA:BB {}\nbroken line\n", to_hex(&[7u8; 32]));
        assert_eq!(parse_peers(&text), m);
    }
}
