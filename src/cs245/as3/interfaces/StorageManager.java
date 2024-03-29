package cs245.as3.interfaces;

import java.util.HashMap;
//相当于磁盘
public interface StorageManager {
	public static class TaggedValue {
		public final long tag;//持久化
		public final byte[] value;//持久化
		
		public TaggedValue(long tag, byte[] value) {
			this.tag = tag;
			this.value = value;
		}
	}

	/**
	 * Reads the current persisted table of values. Should only be called during recovery.
	 * @return a copy of the data (will be empty if no values are stored)
	 */
	public HashMap<Long, TaggedValue> readStoredTable();

	/**
	 * Queues a write to storage with the given key, tag, and value.
	 */
	public void queueWrite(long key, long tag, byte[] value);
}
