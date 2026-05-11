pub mod context;
pub mod memory;
pub mod file_graph;
pub mod observe;
pub mod algorithm;

use context::ContextManager;
use file_graph::FileGraph;
use memory::MemoryStore;
use observe::EventLogger;

pub struct Engine {
    pub context: ContextManager,
    pub file_graph: FileGraph,
    pub memory: MemoryStore,
    pub observer: EventLogger,
}

impl Engine {
    pub fn new(db_path: &str) -> Result<Self, Box<dyn std::error::Error>> {
        Ok(Self {
            context: ContextManager::new(),
            file_graph: FileGraph::new(),
            memory: MemoryStore::new(db_path)?,
            observer: EventLogger::new(db_path)?,
        })
    }

    pub fn process_file_change(&mut self, path: &str, content: &str) -> Result<Vec<String>, Box<dyn std::error::Error>> {
        let old_refs = self.file_graph.get_references(path);
        self.file_graph.update_file(path, content);
        let new_refs = self.file_graph.get_references(path);
        let mut affected: Vec<String> = Vec::new();
        for r in &new_refs { if !old_refs.contains(r) && !affected.contains(r) { affected.push(r.clone()); } }
        for r in &old_refs { if !new_refs.contains(r) && !affected.contains(r) { affected.push(r.clone()); } }
        let impact = self.file_graph.get_impact_set(path);
        for node in impact { if !affected.contains(&node) { affected.push(node); } }
        self.observer.log_event("file_change", &serde_json::json!({"path": path, "added_refs": new_refs.iter().filter(|r| !old_refs.contains(r)).cloned().collect::<Vec<_>>(), "removed_refs": old_refs.iter().filter(|r| !new_refs.contains(r)).cloned().collect::<Vec<_>>(), "affected_count": affected.len()}).to_string())?;
        Ok(affected)
    }

    pub fn get_context_for_query(&self, query: &str, budget: usize) -> String {
        let _ = query;
        self.context.get_active_context_within_budget(budget)
    }

    pub fn search_memory(&self, query: &str, limit: usize) -> Result<Vec<memory::MemoryEntry>, Box<dyn std::error::Error>> {
        self.memory.search(query, limit)
    }
}

#[no_mangle]
pub extern "C" fn engine_create(db_path: *const std::os::raw::c_char) -> *mut Engine {
    let db_path_str = unsafe { std::ffi::CStr::from_ptr(db_path).to_string_lossy().into_owned() };
    match Engine::new(&db_path_str) { Ok(engine) => Box::into_raw(Box::new(engine)), Err(_) => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "C" fn engine_destroy(engine: *mut Engine) {
    if !engine.is_null() { unsafe { drop(Box::from_raw(engine)) }; }
}
