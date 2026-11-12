package com.muto118.quicksave.snapshot;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Streaming XXH64 (seed 0), the hash that names objects in the content store.
 * Port of the reference algorithm (github.com/Cyan4973/xxHash, BSD-2).
 * Not thread-safe; one instance per stream.
 */
public final class Xxh64 {
	private static final long P1 = 0x9E3779B185EBCA87L;
	private static final long P2 = 0xC2B2AE3D27D4EB4FL;
	private static final long P3 = 0x165667B19E3779F9L;
	private static final long P4 = 0x85EBCA77C2B2AE63L;
	private static final long P5 = 0x27D4EB2F165667C5L;
	private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
	private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

	private long v1 = P1 + P2;
	private long v2 = P2;
	private long v3 = 0;
	private long v4 = -P1;
	private long totalLength;
	private final byte[] buffer = new byte[32];
	private int buffered;

	public void update(byte[] data, int offset, int length) {
		this.totalLength += length;
		if (this.buffered + length < 32) {
			System.arraycopy(data, offset, this.buffer, this.buffered, length);
			this.buffered += length;
			return;
		}
		int end = offset + length;
		if (this.buffered > 0) {
			int fill = 32 - this.buffered;
			System.arraycopy(data, offset, this.buffer, this.buffered, fill);
			this.stripe(this.buffer, 0);
			offset += fill;
			this.buffered = 0;
		}
		while (offset + 32 <= end) {
			this.stripe(data, offset);
			offset += 32;
		}
		if (offset < end) {
			System.arraycopy(data, offset, this.buffer, 0, end - offset);
			this.buffered = end - offset;
		}
	}

	private void stripe(byte[] b, int i) {
		this.v1 = round(this.v1, (long) LONG.get(b, i));
		this.v2 = round(this.v2, (long) LONG.get(b, i + 8));
		this.v3 = round(this.v3, (long) LONG.get(b, i + 16));
		this.v4 = round(this.v4, (long) LONG.get(b, i + 24));
	}

	public long digest() {
		long h;
		if (this.totalLength >= 32) {
			h = Long.rotateLeft(this.v1, 1) + Long.rotateLeft(this.v2, 7) + Long.rotateLeft(this.v3, 12) + Long.rotateLeft(this.v4, 18);
			h = mergeRound(h, this.v1);
			h = mergeRound(h, this.v2);
			h = mergeRound(h, this.v3);
			h = mergeRound(h, this.v4);
		} else {
			h = this.v3 + P5;
		}
		h += this.totalLength;
		int i = 0;
		int n = this.buffered;
		byte[] b = this.buffer;
		while (i + 8 <= n) {
			h ^= round(0, (long) LONG.get(b, i));
			h = Long.rotateLeft(h, 27) * P1 + P4;
			i += 8;
		}
		if (i + 4 <= n) {
			h ^= ((int) INT.get(b, i) & 0xFFFFFFFFL) * P1;
			h = Long.rotateLeft(h, 23) * P2 + P3;
			i += 4;
		}
		while (i < n) {
			h ^= (b[i] & 0xFFL) * P5;
			h = Long.rotateLeft(h, 11) * P1;
			i++;
		}
		h ^= h >>> 33;
		h *= P2;
		h ^= h >>> 29;
		h *= P3;
		h ^= h >>> 32;
		return h;
	}

	public String hex() {
		return toHex(this.digest());
	}

	public static String toHex(long hash) {
		String s = Long.toHexString(hash);
		return "0".repeat(16 - s.length()) + s;
	}

	public static long hash(byte[] data) {
		Xxh64 x = new Xxh64();
		x.update(data, 0, data.length);
		return x.digest();
	}

	private static long round(long acc, long input) {
		acc += input * P2;
		acc = Long.rotateLeft(acc, 31);
		return acc * P1;
	}

	private static long mergeRound(long acc, long val) {
		acc ^= round(0, val);
		return acc * P1 + P4;
	}
}
