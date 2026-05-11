use std::hash::{Hash, Hasher};

pub struct BloomFilter {
    bitmap: Vec<u64>,
    num_bits: usize,
    num_hashes: usize,
    count: usize,
}

impl BloomFilter {
    pub fn new(expected_items: usize, false_positive_rate: f64) -> Self {
        let num_bits = Self::optimal_num_bits(expected_items, false_positive_rate);
        let num_hashes = Self::optimal_num_hashes(num_bits, expected_items);
        let num_words = (num_bits + 63) / 64;

        Self {
            bitmap: vec![0u64; num_words],
            num_bits,
            num_hashes,
            count: 0,
        }
    }

    pub fn insert<T: Hash + ?Sized>(&mut self, item: &T) {
        let (hash1, hash2) = Self::double_hash(item);
        for i in 0..self.num_hashes {
            let hash = hash1.wrapping_add((i as u64).wrapping_mul(hash2));
            let bit_index = (hash % self.num_bits as u64) as usize;
            let word_index = bit_index / 64;
            let bit_offset = bit_index % 64;
            self.bitmap[word_index] |= 1u64 << bit_offset;
        }
        self.count += 1;
    }

    pub fn contains<T: Hash + ?Sized>(&self, item: &T) -> bool {
        let (hash1, hash2) = Self::double_hash(item);
        for i in 0..self.num_hashes {
            let hash = hash1.wrapping_add((i as u64).wrapping_mul(hash2));
            let bit_index = (hash % self.num_bits as u64) as usize;
            let word_index = bit_index / 64;
            let bit_offset = bit_index % 64;
            if self.bitmap[word_index] & (1u64 << bit_offset) == 0 {
                return false;
            }
        }
        true
    }

    pub fn estimated_false_positive_rate(&self) -> f64 {
        if self.num_bits == 0 || self.count == 0 {
            return 0.0;
        }
        let k = self.num_hashes as f64;
        let n = self.count as f64;
        let m = self.num_bits as f64;
        (1.0 - (-k * n / m).exp()).powf(k)
    }

    pub fn count(&self) -> usize {
        self.count
    }

    pub fn clear(&mut self) {
        for word in &mut self.bitmap {
            *word = 0;
        }
        self.count = 0;
    }

    fn double_hash<T: Hash + ?Sized>(item: &T) -> (u64, u64) {
        let mut hasher1 = std::collections::hash_map::DefaultHasher::new();
        item.hash(&mut hasher1);
        let hash1 = hasher1.finish();

        let mut hasher2 = std::collections::hash_map::DefaultHasher::new();
        0xDEADBEEFu64.hash(&mut hasher2);
        item.hash(&mut hasher2);
        let hash2 = hasher2.finish();

        if hash2 == 0 {
            (hash1, 1)
        } else {
            (hash1, hash2)
        }
    }

    fn optimal_num_bits(items: usize, fpr: f64) -> usize {
        if items == 0 || fpr <= 0.0 || fpr >= 1.0 {
            return 1024;
        }
        let m = -(items as f64 * fpr.ln()) / (2.0_f64.ln().powi(2));
        (m.ceil() as usize).max(64)
    }

    fn optimal_num_hashes(bits: usize, items: usize) -> usize {
        if items == 0 {
            return 3;
        }
        let k = (bits as f64 / items as f64 * 2.0_f64.ln()).ceil() as usize;
        k.clamp(1, 20)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bloom_filter_basic() {
        let mut bf = BloomFilter::new(1000, 0.01);
        bf.insert("hello");
        bf.insert("world");

        assert!(bf.contains("hello"));
        assert!(bf.contains("world"));
        assert!(!bf.contains("missing"));
    }

    #[test]
    fn test_bloom_filter_false_positive_rate() {
        let mut bf = BloomFilter::new(10000, 0.01);
        for i in 0..10000 {
            bf.insert(&i);
        }

        let fpr = bf.estimated_false_positive_rate();
        assert!(fpr < 0.05, "False positive rate too high: {}", fpr);
    }

    #[test]
    fn test_bloom_filter_clear() {
        let mut bf = BloomFilter::new(100, 0.01);
        bf.insert("test");
        assert!(bf.contains("test"));
        bf.clear();
        assert!(!bf.contains("test"));
        assert_eq!(bf.count(), 0);
    }
}