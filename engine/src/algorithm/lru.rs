use std::collections::HashMap;
use std::hash::Hash;

struct LruEntry<K, V> { value: V, prev: Option<K>, next: Option<K> }

pub struct LruCache<K, V> { capacity: usize, entries: HashMap<K, LruEntry<K, V>>, head: Option<K>, tail: Option<K> }

impl<K: Clone + Hash + Eq, V: Clone> LruCache<K, V> {
    pub fn new(capacity: usize) -> Self { Self { capacity: capacity.max(1), entries: HashMap::new(), head: None, tail: None } }

    pub fn get(&mut self, key: &K) -> Option<V> {
        if self.entries.contains_key(key) { self.move_to_front(key); self.entries.get(key).map(|e| e.value.clone()) } else { None }
    }

    pub fn put(&mut self, key: K, value: V) -> Option<V> {
        if self.entries.contains_key(&key) { self.move_to_front(&key); let old = std::mem::replace(&mut self.entries.get_mut(&key).unwrap().value, value); return Some(old); }
        if self.entries.len() >= self.capacity { self.evict_lru(); }
        let old_head = self.head.take();
        let entry = LruEntry { value, prev: None, next: old_head.clone() };
        if let Some(ref old_head_key) = old_head { if let Some(old_head_entry) = self.entries.get_mut(old_head_key) { old_head_entry.prev = Some(key.clone()); } }
        self.head = Some(key.clone());
        if self.tail.is_none() { self.tail = Some(key.clone()); }
        self.entries.insert(key, entry);
        None
    }

    pub fn remove(&mut self, key: &K) -> Option<V> { let entry = self.entries.remove(key)?; self.unlink_node(&entry); Some(entry.value) }
    pub fn len(&self) -> usize { self.entries.len() }
    pub fn is_empty(&self) -> bool { self.entries.is_empty() }
    pub fn contains(&self, key: &K) -> bool { self.entries.contains_key(key) }
    pub fn peek_lru(&self) -> Option<&V> { self.tail.as_ref().and_then(|k| self.entries.get(k).map(|e| &e.value)) }

    fn move_to_front(&mut self, key: &K) {
        let prev = self.entries.get(key).and_then(|e| e.prev.clone());
        let next = self.entries.get(key).and_then(|e| e.next.clone());
        if self.entries.get(key).is_none() { return; }
        self.unlink_from_list(prev.clone(), next.clone(), key);
        let old_head = self.head.take();
        if let Some(ref old_head_key) = old_head { if let Some(old_head_entry) = self.entries.get_mut(old_head_key) { old_head_entry.prev = Some(key.clone()); } }
        if let Some(e) = self.entries.get_mut(key) { e.prev = None; e.next = old_head; }
        self.head = Some(key.clone());
        if self.tail.is_none() { self.tail = Some(key.clone()); }
    }

    fn unlink_from_list(&mut self, prev: Option<K>, next: Option<K>, key: &K) {
        if let Some(ref prev_key) = prev { if let Some(prev_entry) = self.entries.get_mut(prev_key) { prev_entry.next = next.clone(); } } else if self.head.as_ref() == Some(key) { self.head = next.clone(); }
        if let Some(ref next_key) = next { if let Some(next_entry) = self.entries.get_mut(next_key) { next_entry.prev = prev; } } else if self.tail.as_ref() == Some(key) { self.tail = self.entries.get(key).and_then(|e| e.prev.clone()); }
    }

    fn unlink_node(&mut self, entry: &LruEntry<K, V>) {
        if let Some(ref prev_key) = entry.prev { if let Some(prev_entry) = self.entries.get_mut(prev_key) { prev_entry.next = entry.next.clone(); } } else { self.head = entry.next.clone(); }
        if let Some(ref next_key) = entry.next { if let Some(next_entry) = self.entries.get_mut(next_key) { next_entry.prev = entry.prev.clone(); } } else { self.tail = entry.prev.clone(); }
    }

    fn evict_lru(&mut self) {
        let tail_key = match self.tail.clone() { Some(k) => k, None => return };
        let entry = match self.entries.remove(&tail_key) { Some(e) => e, None => return };
        if let Some(ref prev_key) = entry.prev { if let Some(prev_entry) = self.entries.get_mut(prev_key) { prev_entry.next = None; } } else { self.head = None; }
        self.tail = entry.prev.clone();
    }
}

#[cfg(test)] mod tests { use super::*; #[test] fn test_lru_basic() { let mut cache = LruCache::new(3); cache.put("a", 1); cache.put("b", 2); cache.put("c", 3); assert_eq!(cache.get(&"a"), Some(1)); assert_eq!(cache.get(&"b"), Some(2)); assert_eq!(cache.get(&"c"), Some(3)); } #[test] fn test_lru_eviction() { let mut cache = LruCache::new(2); cache.put("a", 1); cache.put("b", 2); cache.put("c", 3); assert_eq!(cache.get(&"a"), None); assert_eq!(cache.get(&"b"), Some(2)); assert_eq!(cache.get(&"c"), Some(3)); } }
}