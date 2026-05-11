use sha2::{Sha256, Digest};
use serde::{Serialize, Deserialize};
use std::collections::HashMap;
use std::sync::Mutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event { pub event_type: String, pub timestamp: i64, pub data: String }

pub struct EventLogger { conn: rusqlite::Connection, buffer: MutEx<Vec<Event>> }

impl EventLogger {
    pub fn new(db_path: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let conn = rusqlite::Connection::open(db_path)?;
        conn.execute_batch("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, event_type TEXT NOT NULL, timestamp INTEGER NOT NULL, data TEXT NOT NULL);")?;
        Ok(Self { conn, buffer: Mutex::new(Vec::new()) })
    }

    pub fn log_event(&self, event_type: &str, data: &str) -> Result<(), Box<dyn std::error::Error>> {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs() as i64;
        self.conn.execute("INSERT INTO events (event_type, timestamp, data) VALUES (?1, ?2, ?3)", rusqlite::params![event_type, now, data])?;
        Ok(())
    }

    pub fn query_events(&self, event_type: Option<&str>, limit: usize) -> Result<Vec<Event>, Box<dyn std::error::Error>> {
        let query = match event_type { Some(_) => "SELECT event_type, timestamp, data FROM events WHERE event_type = ?1 ORDER BY timestamp DESC LIMIT ?2", None => "SELECT event_type, timestamp, data FROM events ORDER BY timestamp DESC LIMIT ?1" };
        let mut stmt = self.conn.prepare(query)?;
        let rows = match event_type { Some(t) => stmt.query_map(rusqlite::params![t, limit as i64], |row| Ok(Event { event_type: row.get(0)?, timestamp: row.get(1)?, data: row.get(2)? }))?, None => stmt.query_map(rusqlite::params![limit as i64], |row| Ok(Event { event_type: row.get(0)?, timestamp: row.get(1)?, data: row.get(2)? }))? };
        Ok(rows.filter_map(|r| r.ok()).collect())
    }
}