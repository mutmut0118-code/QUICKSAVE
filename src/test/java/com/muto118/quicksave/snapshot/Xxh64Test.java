package com.muto118.quicksave.snapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Xxh64Test {
	/** Expected values from the reference implementation (python xxhash 3.x, xxh64, seed 0). */
	private static final Object[][] VECTORS = {
			{0, "ef46db3751d8e999"},
			{1, "a96c7f0ce858bbb7"},
			{3, "56e6957632a487f9"},
			{4, "c60d15b1e3ff8f04"},
			{7, "afbefc3d6c6f9a8e"},
			{8, "3da5c7aa269683e0"},
			{15, "ae2a37eb9357caa7"},
			{31, "4a74f3a1a39ad4a1"},
			{32, "8d57d6a4671cc43d"},
			{33, "62c9fd21ed857664"},
			{63, "5c320a0d2707057f"},
			{64, "7bbabbc45729d17e"},
			{100, "efa0ad2d3e70c151"},
			{1000, "99594f4828043d35"},
			{100000, "3ac9cbc5a9b7f843"},
	};

	static byte[] pattern(int n) {
		byte[] data = new byte[n];
		for (int i = 0; i < n; i++) {
			data[i] = (byte) (i * 31 + 7);
		}
		return data;
	}

	@Test
	void matchesReferenceVectors() {
		for (Object[] vector : VECTORS) {
			int n = (Integer) vector[0];
			assertEquals(vector[1], Xxh64.toHex(Xxh64.hash(pattern(n))), "length " + n);
		}
	}

	@Test
	void streamingInAnyChunkSizeMatchesOneShot() {
		byte[] data = pattern(100000);
		String expected = Xxh64.toHex(Xxh64.hash(data));
		for (int chunk : new int[] {1, 3, 7, 31, 32, 33, 1000, 65536}) {
			Xxh64 x = new Xxh64();
			for (int i = 0; i < data.length; i += chunk) {
				x.update(data, i, Math.min(chunk, data.length - i));
			}
			assertEquals(expected, x.hex(), "chunk " + chunk);
		}
	}
}
