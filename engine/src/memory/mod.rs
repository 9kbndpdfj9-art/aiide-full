use rusqlite::{Connection, params};
use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemoryEntry {
    pub id: i64,
    pub content: String,
    pub memory_type: MemoryType,
    pub project: Option<String>,
    pub tags: Vec<String>,
    pub importance: f64,
    pub created_at: i64,
    pub accessed_at: i64,
    pub access_count: i64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub enum MemoryType { Ephemeral = 0, Working = 1, LongTerm = 2, Meta = 3 }

pub struct MemoryStore { conn: Connection }

impl MemoryStore {
    pub fn new(db_path: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let conn = Connection::open(db_path)?;
        conn.execute_batch("CREATE TABLE IF NOT EXISTS memories (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, memory_type INTEGER NOT NULL DEFAULT 1, project TEXT, tags TEXT DEFAULT '[]', importance REAL DEFAULT 0.5, created_at INTEGER NOT NULL, accessed_at INTEGER NOT NULL, access_count INTEGER DEFAULT 0, content_hash TEXT UNIQUE); CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(memory_type); CREATE INDEX IF NOT EXISTS idx_memories_project ON memories(project); CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance DESC); CREATE INDEX IF NOT EXISTS idx_memories_hash ON memories(content_hash);")?;
        Ok(Self { conn })
    }

    pub fn store(&self, content: &str, memory_type: MemoryType, project: Option<&str>, tags: Vec<String>, importance: f64) -> Result<i64, Box<dyn std::error::Error>> {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs() as i64;
        let tags_json = serde_json::to_string(&tags)?;
        let hash = Self::hash_content(content);
        let existing_id: Option<i64> = self.conn.query_row("SELECT id FROM memories WHERE content_hash = ?1", params![hash], |row| row.get(0)).ok();
        if let Some(id) = existing_id { self.conn.execute("UPDATE memories SET importance = MAX(importance, ?1), accessed_at = ?2, access_count = access_count + 1, tags = ?3 WHERE id = ?4", params![importance, now, tags_json, id])?; return Ok(id); }
        self.conn.execute("INSERT INTO memories (content, memory_type, project, tags, importance, created_at, accessed_at, access_count, content_hash) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 0, ?8)", params![content, memory_type as i32, project, tags_json, importance, now, now, hash])?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn search(&self, query: &str, limit: usize) -> Result<Vec<MemoryEntry>, Box<dyn std::error::Error>> {
        let pattern = format!("%{}%", query.replace('%', "\\%").replace('_', "\\_"));
        let mut stmt = self.conn.prepare("SELECT id, content, memory_type, project, tags, importance, created_at, accessed_at, access_count FROM memories WHERE content LIKE ?1 ESCAPE '\\' ORDER BY importance DESC, accessed_at DESC LIMIT ?2")?;
        let entries = stmt.query_map(params![pattern, limit as i64], |row| Ok(Self::row_to_entry(row)))?.filter_map(|e| e.ok()).collect();
        Ok(entries)
    }

    pub fn touch(&self, id: i64) -> Result<(), Box<dyn std::error::Error>> {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs() as i64;
        self.conn.execute("UPDATE memories SET accessed_at = ?1, access_count = access_count + 1 WHERE id = ?2", params![now, id])?;
        Ok(())
    }

    pub fn decay_importance(&self, max_age_secs: i64) -> Result<usize, Box<dyn std::error::Error>> {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs() as i64;
        let cutoff = now - max_age_secs;
        let decayed = self.conn.execute("UPDATE memories SET importance = importance * 0.9 WHERE accessed_at < ?1", params![cutoff])?;
        let deleted = self.conn.execute("DELETE FROM memories WHERE importance < 0.05 AND memory_type = ?1", params![MemoryType::Ephemeral as i32])?;
        Ok(decayed + deleted)
    }

    fn row_to_entry(row: &rusqlite::Row) -> MemoryEntry {
        let tags_str: String = row.get(4).unwrap_or_default();
        let tags: Vec<String> = serde_json::from_str(&tags_str).unwrap_or_default();
        MemoryEntry { id: row.get(0).unwrap_or(0), content: row.get(1).unwrap_or_default(), memory_type: match row.get::<_, i32>(2).unwrap_or(1) { 0 => MemoryType::Ephemeral, 1 => MemoryType::Working, 2 => MemoryType::LongTerm, 3 => MemoryType::Meta, _ => MemoryType::Working }, project: row.get(3).unwrap_or(None), tags, importance: row.get(5).unwrap_or(0.5), created_at: row.get(6).unwrap_or(0), accessed_at: row.get(7).unwrap_or(0), access_count: row.get(8).unwrap_or(0) }
    }

    fn hash_content(content: &str) -> String { use sha2::{Sha256, Digest}; let mut hasher = Sha256::new(); hasher.update(content.as_bytes()); hex::encode(hasher.finalize()) }
}