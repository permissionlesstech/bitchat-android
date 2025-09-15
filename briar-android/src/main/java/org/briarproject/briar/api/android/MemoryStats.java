package org.briarproject.briar.api.android;

import java.io.Serializable;

import javax.annotation.concurrent.Immutable;

/**
 * Memory usage stats to be included in feedback and crash reports. This class
 * is {@link Serializable} so it can be passed from the crashed process to the
 * crash reporter process.
 */
@Immutable
public class MemoryStats implements Serializable {

	public final long systemMemoryTotal;
	public final long systemMemoryFree;
	public final long systemMemoryThreshold;
	public final boolean systemMemoryLow;
	public final long vmMemoryTotal;
	public final long vmMemoryFree;
	public final long vmMemoryMax;
	public final long nativeHeapTotal;
	public final long nativeHeapAllocated;
	public final long nativeHeapFree;

	public MemoryStats(long systemMemoryTotal, long systemMemoryFree,
			long systemMemoryThreshold, boolean systemMemoryLow,
			long vmMemoryTotal, long vmMemoryFree, long vmMemoryMax,
			long nativeHeapTotal, long nativeHeapAllocated,
			long nativeHeapFree) {
		this.systemMemoryTotal = systemMemoryTotal;
		this.systemMemoryFree = systemMemoryFree;
		this.systemMemoryThreshold = systemMemoryThreshold;
		this.systemMemoryLow = systemMemoryLow;
		this.vmMemoryTotal = vmMemoryTotal;
		this.vmMemoryFree = vmMemoryFree;
		this.vmMemoryMax = vmMemoryMax;
		this.nativeHeapTotal = nativeHeapTotal;
		this.nativeHeapAllocated = nativeHeapAllocated;
		this.nativeHeapFree = nativeHeapFree;
	}
}
