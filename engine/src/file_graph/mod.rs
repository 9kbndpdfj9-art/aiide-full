use petgraph::graph::{DiGraph, NodeIndex};
use petgraph::algo::dijkstra;
use petgraph::visit::EdgeRef;
use std::collections::HashMap;
use std::sync::LazyLock;

static IMPORT_PATTERNS: LazyLock<Vec<(regex::Regex, bool)>> = LazyLock::new(|| {
    vec![
        (regex::Regex::new(r#"import\s+.*?\s+from\s+['\"](.+?)['\"]"#).unwrap(), true),
        (regex::Regex::new(r#"require\(\s*['\"](.+?)['\"]\s*\)"#).unwrap(), true),
        (regex::Regex::new(r#"from\s+['\"](.+?)['\"]\s+import"#).unwrap(), true),
        (regex::Regex::new(r#"#include\s*[<"](.+?)[>]"#).unwrap(), false),
        (regex::Regex::new(r#"using\s+(.+?);"#).unwrap(), false),
        (regex::Regex::new(r#"use\s+([\w:]+)"#).unwrap(), false),
        (regex::Regex::new(r#"mod\s+(\w+)"#).unwrap(), false),
    ]
});

static EXPORT_PATTERNS: LazyLock<Vec<regex::Regex>> = LazyLock::new(|| {
    vec![
        regex::Regex::new(r#"export\s+(?:default\s+)?(?:function|class|const|let|var)\s+(\w+)"#).unwrap(),
        regex::Regex::new(r#"export\s*\{\s*([^}]+)\s*\}"#).unwrap(),
        regex::Regex::new(r#"module\.exports\s*=\s*(\w+)"#).unwrap(),
        regex::Regex::new(r#"pub\s+fn\s+(\w+)"#).unwrap(),
        regex::Regex::new(r#"pub\s+struct\s+(\w+)"#).unwrap(),
        regex::Regex::new(r#"pub\s+enum\s+(\w+)"#).unwrap(),
    ]
});

#[derive(Debug, Clone)]
pub struct FileNode { pub path: String, pub imports: Vec<String>, pub exports: Vec<String>, pub hash: String }

pub struct FileGraph { graph: DiGraph<FileNode, f64>, path_index: HashMap<String, NodeIndex> }

impl FileGraph {
    pub fn new() -> Self { Self { graph: DiGraph::new(), path_index: HashMap::new() } }

    pub fn update_file(&mut self, path: &str, content: &str) {
        let imports = Self::extract_imports(content);
        let exports = Self::extract_exports(content);
        let hash = Self::content_hash(content);
        let node = FileNode { path: path.to_string(), imports: imports.clone(), exports, hash };
        if let Some(&idx) = self.path_index.get(path) { self.graph[idx] = node; } else { let idx = self.graph.add_node(node); self.path_index.insert(path.to_string(), idx); }
        self.rebuild_edges_for(path, &imports);
        self.rebuild_incoming_edges_for(path);
    }

    pub fn remove_file(&mut self, path: &str) { if let Some(idx) = self.path_index.remove(path) { self.graph.remove_node(idx); } }

    pub fn get_references(&self, path: &str) -> Vec<String> {
        let mut refs = Vec::new();
        if let Some(&idx) = self.path_index.get(path) { for neighbor in self.graph.neighbors_directed(idx, petgraph::Direction::Outgoing) { if let Some(node) = self.graph.node_weight(neighbor) { refs.push(node.path.clone()); } } }
        refs
    }

    pub fn get_referenced_by(&self, path: &str) -> Vec<String> {
        let mut refs = Vec::new();
        if let Some(&idx) = self.path_index.get(path) { for neighbor in self.graph.neighbors_directed(idx, petgraph::Direction::Incoming) { if let Some(node) = self.graph.node_weight(neighbor) { refs.push(node.path.clone()); } } }
        refs
    }

    pub fn get_impact_set(&self, path: &str) -> Vec<String> {
        let mut impact = Vec::new();
        if let Some(&idx) = self.path_index.get(path) { let distances: HashMap<NodeIndex, f64> = dijkstra(&self.graph, idx, None, |e| *e.weight()); for (&node_idx, &dist) in &distances { if node_idx != idx && dist <= 2.0 { if let Some(node) = self.graph.node_weight(node_idx) { impact.push(node.path.clone()); } } } }
        impact
    }

    pub fn file_count(&self) -> usize { self.path_index.len() }

    fn rebuild_edges_for(&mut self, path: &str, imports: &[String]) {
        let idx = match self.path_index.get(path) { Some(&i) => i, None => return };
        let edges_to_remove: Vec<(NodeIndex, NodeIndex)> = self.graph.edges_directed(idx, petgraph::Direction::Outgoing).map(|e| (idx, e.target())).collect();
        for (src, dst) in edges_to_remove { if let Some(edge_idx) = self.graph.find_edge(src, dst) { self.graph.remove_edge(edge_idx); } }
        for import_path in imports { if let Some(&target_idx) = self.path_index.get(import_path) { if self.graph.find_edge(idx, target_idx).is_none() { self.graph.add_edge(idx, target_idx, 1.0); } } }
    }

    fn rebuild_incoming_edges_for(&mut self, path: &str) {
        let target_idx = match self.path_index.get(path) { Some(&i) => i, None => return };
        let paths_that_import_this: Vec<NodeIndex> = self.path_index.iter().filter(|(p, _)| *p != path).filter_map(|(other_path, &other_idx)| { let node = &self.graph[other_idx]; if node.imports.contains(&path.to_string()) { Some(other_idx) } else { None } }).collect();
        for source_idx in paths_that_import_this { if self.graph.find_edge(source_idx, target_idx).is_none() { self.graph.add_edge(source_idx, target_idx, 1.0); } }
    }

    fn extract_imports(content: &str) -> Vec<String> {
        let mut imports = Vec::new();
        let mut in_block_comment = false;
        for line in content.lines() {
            let trimmed = line.trim();
            if in_block_comment { if trimmed.contains("*/") { in_block_comment = false; } continue; }
            if trimmed.starts_with("/*") { if !trimmed.contains("*/") { in_block_comment = true; } continue; }
            for (re, is_js) in IMPORT_PATTERNS.iter() { for cap in re.captures_iter(trimmed) { if let Some(m) = cap.get(1) { let mut import_path = m.as_str().to_string(); if *is_js && !import_path.starts_with('.') { continue; } if *is_js { import_path = import_path.trim_start_matches("./").to_string(); if !import_path.ends_with(".js") && !import_path.ends_with(".ts") && !import_path.ends_with(".jsx") && !import_path.ends_with(".tsx") { import_path.push_str(".js"); } } if !imports.contains(&import_path) { imports.push(import_path); } } } }
        }
        imports
    }

    fn extract_exports(content: &str) -> Vec<String> {
        let mut exports = Vec::new();
        let mut in_block_comment = false;
        for line in content.lines() {
            let trimmed = line.trim();
            if in_block_comment { if trimmed.contains("*/") { in_block_comment = false; } continue; }
            if trimmed.starts_with("/*") { if !trimmed.contains("*/") { in_block_comment = true; } continue; }
            for re in EXPORT_PATTERNS.iter() { if let Some(cap) = re.captures(trimmed) { if let Some(m) = cap.get(1) { for name in m.as_str().split(',') { let name = name.trim().split_whitespace().last().unwrap_or("").trim().trim_start_matches("pub ").trim(); if !name.is_empty() && !exports.contains(&name.to_string()) { exports.push(name.to_string()); } } } } }
        }
        exports
    }

    fn content_hash(content: &str) -> String { use sha2::{Sha256, Digest}; let mut hasher = Sha256::new(); hasher.update(content.as_bytes()); hex::encode(hasher.finalize()) }
}

#[cfg(test)] mod tests { use super::*; #[test] fn test_file_graph_basic() { let mut fg = FileGraph::new(); fg.update_file("main.js", "import { foo } from './utils.js'"); fg.update_file("utils.js", "export function foo() {}"); let refs = fg.get_references("main.js"); assert!(refs.contains(&"utils.js".to_string())); } }
}