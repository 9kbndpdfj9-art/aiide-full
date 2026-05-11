use sha2::{Sha256, Digest};
use std::collections::HashMap;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct ContextEntry { pub id: String, pub content: String, pub summary: Option<String>, pub level: CompressionLevel, pub token_estimate: usize, pub priority: f64, pub tags: Vec<String> }

#[derive(Debug, Clone, Copy, serde::Serialize, serde::Deserialize, PartialEq, PartialOrd)]
pub enum CompressionLevel { L0 = 0, L1 = 1, L2 = 2, L3 = 3 }

pub struct ContextManager { entries: HashMap<String, ContextEntry>, max_tokens: usize, current_tokens: usize }

impl ContextManager {
    pub fn new() -> Self { Self { entries: HashMap::new(), max_tokens: 120000, current_tokens: 0 } }
    pub fn with_max_tokens(max_tokens: usize) -> Self { Self { entries: HashMap::new(), max_tokens, current_tokens: 0 } }

    pub fn add_entry(&mut self, content: &str, priority: f64, tags: Vec<String>) -> String {
        let id = Self::hash_content(content);
        let token_estimate = Self::estimate_tokens(content);
        if let Some(existing) = self.entries.get_mut(&id) { existing.priority = existing.priority.max(priority); existing.tags.extend(tags); existing.tags.sort(); existing.tags.dedup(); return id; }
        let entry = ContextEntry { id: id.clone(), content: content.to_string(), summary: None, level: CompressionLevel::L0, token_estimate, priority, tags };
        self.current_tokens += token_estimate;
        self.entries.insert(id.clone(), entry);
        id
    }

    pub fn get_usage_ratio(&self) -> f64 { if self.max_tokens == 0 { return 1.0; } self.current_tokens as f64 / self.max_tokens as f64 }
    pub fn should_compress(&self) -> Option<CompressionLevel> { let ratio = self.get_usage_ratio(); if ratio > 0.95 { Some(CompressionLevel::L3) } else if ratio > 0.85 { Some(CompressionLevel::L2) } else if ratio > 0.70 { Some(CompressionLevel::L1) } else { None } }

    pub fn compress_to_level(&mut self, target_level: CompressionLevel) -> Vec<String> {
        let mut compressed = Vec::new();
        let mut entries: Vec<&mut ContextEntry> = self.entries.values_mut().collect();
        entries.sort_by(|a, b| a.priority.partial_cmp(&b.priority).unwrap_or(std::cmp::Ordering::Equal));
        for entry in entries {
            if (entry.level as u8) >= (target_level as u8) { continue; }
            let old_tokens = entry.token_estimate;
            let (new_content, new_tokens) = match target_level { CompressionLevel::L1 => Self::compress_l1(&entry.content), CompressionLevel::L2 => Self::compress_l2(&entry.content), CompressionLevel::L3 => Self::compress_l3(&entry.content), CompressionLevel::L0 => (entry.content.clone(), entry.token_estimate) };
            entry.summary = Some(new_content); entry.level = target_level; entry.token_estimate = new_tokens;
            self.current_tokens = self.current_tokens.saturating_sub(old_tokens) + new_tokens;
            compressed.push(format!("{}: {} -> {} tokens", entry.id.chars().take(8).collect::<String>(), old_tokens, new_tokens));
        }
        compressed
    }

    pub fn auto_compress(&mut self) -> Option<CompressionLevel> { let level = self.should_compress()?; self.compress_to_level(level); Some(level) }

    pub fn get_active_context(&self) -> String {
        let mut result = String::new();
        let mut entries: Vec<&ContextEntry> = self.entries.values().collect();
        entries.sort_by(|a, b| b.priority.partial_cmp(&a.priority).unwrap_or(std::cmp::Ordering::Equal));
        for entry in entries { let content = match entry.level { CompressionLevel::L0 => &entry.content, _ => entry.summary.as_ref().unwrap_or(&entry.content) }; result.push_str(content); result.push('\n'); }
        result
    }

    pub fn get_active_context_within_budget(&self, budget: usize) -> String {
        let mut result = String::new(); let mut used = 0;
        let mut entries: Vec<&ContextEntry> = self.entries.values().collect();
        entries.sort_by(|a, b| b.priority.partial_cmp(&a.priority).unwrap_or(std::cmp::Ordering::Equal));
        for entry in entries { let content = match entry.level { CompressionLevel::L0 => &entry.content, _ => entry.summary.as_ref().unwrap_or(&entry.content) }; let tokens = Self::estimate_tokens(content); if used + tokens > budget { continue; } result.push_str(content); result.push('\n'); used += tokens; }
        result
    }

    pub fn get_entry(&self, id: &str) -> Option<&ContextEntry> { self.entries.get(id) }
    pub fn remove_entry(&mut self, id: &str) { if let Some(entry) = self.entries.remove(id) { self.current_tokens = self.current_tokens.saturating_sub(entry.token_estimate); } }
    pub fn clear(&mut self) { self.entries.clear(); self.current_tokens = 0; }
    pub fn entry_count(&self) -> usize { self.entries.len() }

    fn compress_l1(content: &str) -> (String, usize) {
        let cleaned = Self::strip_comments(content); let mut result = String::new(); let mut in_block = false; let mut block_lines = 0; let mut brace_depth = 0;
        for line in cleaned.lines() {
            let trimmed = line.trim(); if trimmed.is_empty() { continue; }
            let is_signature = trimmed.starts_with("function ") || trimmed.starts_with("class ") || trimmed.starts_with("def ") || trimmed.starts_with("pub fn ") || trimmed.starts_with("pub struct ") || trimmed.starts_with("fun ") || trimmed.starts_with("val ") || trimmed.starts_with("var ") || trimmed.starts_with("const ");
            if is_signature { result.push_str(line); result.push('\n'); in_block = true; block_lines = 0; } else if in_block { block_lines += 1; brace_depth += trimmed.chars().filter(|c| *c == '{').count() as i32; brace_depth -= trimmed.chars().filter(|c| *c == '}').count() as i32; if block_lines <= 3 { result.push_str(line); result.push('\n'); } else if block_lines == 4 { result.push_str("  // ... compressed ...\n"); } if brace_depth <= 0 && block_lines > 1 { in_block = false; } } else { result.push_str(line); result.push('\n'); }
        }
        (result, Self::estimate_tokens(&result))
    }

    fn compress_l2(content: &str) -> (String, usize) {
        let cleaned = Self::strip_comments(content); let mut signatures = Vec::new();
        for line in cleaned.lines() { let trimmed = line.trim(); if trimmed.is_empty() { continue; } if trimmed.starts_with("function ") || trimmed.starts_with("class ") || trimmed.starts_with("def ") || trimmed.starts_with("pub fn ") || trimmed.starts_with("pub struct ") || trimmed.starts_with("fun ") || trimmed.starts_with("val ") || trimmed.starts_with("var ") || trimmed.starts_with("const ") { let sig = if trimmed.len() > 120 { format!("{}...", &trimmed[..120]) } else { trimmed.to_string() }; signatures.push(sig); } }
        let result = format!("// [L2 Compressed - {} signatures]\n{}", signatures.len(), signatures.join("\n"));
        (result, Self::estimate_tokens(&result))
    }

    fn compress_l3(content: &str) -> (String, usize) { let keywords = Self::extract_keywords(content); let result = format!("// [L3 Index - keywords: {}]", keywords.join(", ")); (result, Self::estimate_tokens(&result)) }

    fn extract_keywords(content: &str) -> Vec<String> {
        let stop_words = ["the", "a", "an", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "may", "might", "must", "shall", "can", "need", "dare", "ought", "used", "to", "of", "in", "for", "on", "with", "at", "by", "from", "as", "into", "through", "during", "before", "after", "above", "below", "between", "under", "and", "but", "or", "nor", "not", "so", "yet", "both", "either", "neither", "each", "every", "all", "any", "few", "more", "most", "other", "some", "such", "if", "else", "return", "const", "let", "var", "function", "class", "this", "new", "true", "false", "null", "undefined", "void", "static", "public", "private", "protected", "override", "final", "abstract", "impl"];
        let mut freq: HashMap<String, usize> = HashMap::new();
        for word in content.split(|c: char| !c.is_alphanumeric() && c != '_') { let w = word.to_lowercase(); if w.len() < 2 || stop_words.contains(&w.as_str()) { continue; } *freq.entry(w).or_insert(0) += 1; }
        let mut keywords: Vec<(String, usize)> = freq.into_iter().collect(); keywords.sort_by(|a, b| b.1.cmp(&a.1)); keywords.truncate(20); keywords.into_iter().map(|(w, _)| w).collect()
    }

    fn strip_comments(content: &str) -> String {
        let mut result = String::new(); let mut in_block_comment = false; let mut in_string = false; let mut string_char = ' '; let chars: Vec<char> = content.chars().collect(); let mut i = 0;
        while i < chars.len() {
            if in_block_comment { if i + 1 < chars.len() && chars[i] == '*' && chars[i + 1] == '/' { in_block_comment = false; i += 2; continue; } i += 1; continue; }
            if in_string { if chars[i] == '\\' && i + 1 < chars.len() { result.push(chars[i]); result.push(chars[i + 1]); i += 2; continue; } if chars[i] == string_char { in_string = false; } result.push(chars[i]); i += 1; continue; }
            if i + 1 < chars.len() && chars[i] == '/' && chars[i + 1] == '/' { while i < chars.len() && chars[i] != '\n' { i += 1; } continue; }
            if i + 1 < chars.len() && chars[i] == '/' && chars[i + 1] == '*' { in_block_comment = true; i += 2; continue; }
            if chars[i] == '"' || chars[i] == '\'' || chars[i] == '`' { in_string = true; string_char = chars[i]; result.push(chars[i]); i += 1; continue; }
            result.push(chars[i]); i += 1;
        }
        result
    }

    fn estimate_tokens(text: &str) -> usize { let char_count = text.chars().count(); let word_count = text.split_whitespace().count(); (char_count / 4).max(word_count * 4 / 3) }
    fn hash_content(content: &str) -> String { let mut hasher = Sha256::new(); hasher.update(content.as_bytes()); hex::encode(hasher.finalize()) }
}

#[cfg(test)] mod tests { use super::*; #[test] fn test_context_add_and_get() { let mut cm = ContextManager::new(); let id = cm.add_entry("fn main() { println!(\"hello\"); }", 1.0, vec!["rust".to_string()]); assert!(cm.get_entry(&id).is_some()); assert_eq!(cm.entry_count(), 1); } }
}