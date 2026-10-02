//! Weighted command selection using normalized cumulative weights.
//!
//! Command selection intentionally uses a thread-local RNG (not the Java LCG):
//! only key generation must be cross-engine deterministic.

use std::cell::RefCell;

use crate::command::Command;

// A small, fast xorshift RNG, seeded per-thread. Command selection does not need
// to be reproducible across engines, only reasonably uniform.
thread_local! {
    static RNG: RefCell<u64> = RefCell::new(seed_from_thread());
}

fn seed_from_thread() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0);
    // Mix in the thread id-ish address so peers seeded in the same nanosecond
    // still diverge.
    let stack_marker = &nanos as *const _ as u64;
    nanos ^ stack_marker.rotate_left(17) ^ 0x9E3779B97F4A7C15
}

fn next_unit_float() -> f64 {
    RNG.with(|cell| {
        let mut x = *cell.borrow();
        // xorshift64
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        *cell.borrow_mut() = x;
        // Top 53 bits → [0, 1).
        (x >> 11) as f64 / (1u64 << 53) as f64
    })
}

/// Selects a command per its relative weight.
pub struct CommandSelector {
    commands: Vec<Command>,
    cumulative_weights: Vec<f64>,
}

impl CommandSelector {
    pub fn new(commands: Vec<Command>) -> Self {
        let cumulative_weights = Self::build_cumulative_weights(&commands);
        CommandSelector {
            commands,
            cumulative_weights,
        }
    }

    pub fn select(&self) -> &Command {
        let r = next_unit_float();
        for (index, threshold) in self.cumulative_weights.iter().enumerate() {
            if r <= *threshold {
                return &self.commands[index];
            }
        }
        // Fallback for floating-point edge cases.
        self.commands.last().expect("selector has no commands")
    }

    fn build_cumulative_weights(commands: &[Command]) -> Vec<f64> {
        let total: f64 = commands.iter().map(|c| c.weight()).sum();
        let total = if total == 0.0 { 1.0 } else { total };
        let mut cumulative = Vec::with_capacity(commands.len());
        let mut running = 0.0;
        for command in commands {
            running += command.weight() / total;
            cumulative.push(running);
        }
        cumulative
    }
}
