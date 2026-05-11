use std::collections::HashMap;

pub struct ConsistentHash<T: Clone> {
    ring: HashMap<u64, T>,
    virtual_nodes: usize,
    sorted_keys: Vec<u64>,
}

impl<T: Clone + Eq + std::hash::Hash + std::fmt::Debug> ConsistentHash<T> {
    pub fn new(virtual_nodes: usize) -> Self {
        Self {
            ring: HashMap::new(),
            virtual_nodes: virtual_nodes.max(1),
            sorted_keys: Vec::new(),
        }
    }

    pub fn add_node(&mut self, node: T) {
        for i in 0..self.virtual_nodes {
            let key = Self::hash_node(&node, i);
            self.ring.insert(key, node.clone());
        }
        self.rebuild_sorted_keys();
    }

    pub fn remove_node(&mut self, node: &T) {
        for i in 0..self.virtual_nodes {
            let key = Self::hash_node(node, i);
            self.ring.remove(&key);
        }
        self.rebuild_sorted_keys();
    }

    pub fn get_node(&self, key: &str) -> Option<&T> {
        if self.sorted_keys.is_empty() {
            return None;
        }

        let hash = Self::hash_key(key);

        match self.sorted_keys.binary_search(&hash) {
            Ok(idx) => self.ring.get(&self.sorted_keys[idx]),
            Err(idx) => {
                let wrapped_idx = idx % self.sorted_keys.len();
                self.ring.get(&self.sorted_keys[wrapped_idx])
            }
        }
    }

    pub fn node_count(&self) -> usize {
        let mut unique = std::collections::HashSet::new();
        for node in self.ring.values() {
            unique.insert(format!("{:?}", node));
        }
        unique.len()
    }

    fn hash_key(key: &str) -> u64 {
        use std::hash::{Hash, Hasher};
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        key.hash(&mut hasher);
        hasher.finish()
    }

    fn hash_node<H: std::hash::Hash>(node: &H, replica: usize) -> u64 {
        use std::hash::{Hash, Hasher};
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        node.hash(&mut hasher);
        replica.hash(&mut hasher);
        hasher.finish()
    }

    fn rebuild_sorted_keys(&mut self) {
        self.sorted_keys = self.ring.keys().cloned().collect();
        self.sorted_keys.sort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_consistent_hash_basic() {
        let mut ch = ConsistentHash::new(100);
        ch.add_node("node-a");
        ch.add_node("node-b");

        let node = ch.get_node("some-key");
        assert!(node.is_some());
        assert!(node.unwrap() == &"node-a" || node.unwrap() == &"node-b");
    }

    #[test]
    fn test_consistent_hash_deterministic() {
        let mut ch = ConsistentHash::new(100);
        ch.add_node("node-a");
        ch.add_node("node-b");

        let first = ch.get_node("test-key");
        let second = ch.get_node("test-key");
        assert_eq!(first, second);
    }

    #[test]
    fn test_consistent_hash_empty() {
        let ch: ConsistentHash<&str> = ConsistentHash::new(100);
        assert!(ch.get_node("key").is_none());
    }

    #[test]
    fn test_consistent_hash_remove() {
        let mut ch = ConsistentHash::new(100);
        ch.add_node("node-a");
        ch.add_node("node-b");
        ch.remove_node(&"node-a");

        let node = ch.get_node("some-key");
        assert_eq!(node, Some(&"node-b"));
    }
}