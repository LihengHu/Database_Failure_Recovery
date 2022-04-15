package cs245.as3;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cs245.as3.driver.LogManagerTests;
import cs245.as3.interfaces.LogManager;
import cs245.as3.interfaces.StorageManager;
import cs245.as3.interfaces.StorageManager.TaggedValue;




/**
 * You will implement this class.
 *
 * The implementation we have provided below performs atomic transactions but the changes are not durable.
 * Feel free to replace any of the data structures in your implementation, though the instructor solution includes
 * the same data structures (with additional fields) and uses the same strategy of buffering writes until commit.
 *
 * Your implementation need not be threadsafe, i.e. no methods of TransactionManager are ever called concurrently.
 *
 * You can assume that the constructor and initAndRecover() are both called before any of the other methods.
 */
public class TransactionManager {
	/**
	 * 日志控制器
	 */
	private LogManager logManager;
	/**
	 * 持久化控制器
	 */
	private StorageManager storageManager;

	class WritesetEntry {
		public long key;
		public byte[] value;
		public WritesetEntry(long key, byte[] value) {
			this.key = key;
			this.value = value;
		}
	}
	/**
	  * Holds the latest value for each key.
	  */
	private HashMap<Long, TaggedValue> latestValues;//事务管理器里
	/**
	  * Hold on to writesets until commit.
	  */
	private HashMap<Long, ArrayList<WritesetEntry>> writesets;

	public TransactionManager() {
		writesets = new HashMap<>();
		//see initAndRecover
		latestValues = null;
	}

	/**
	 * Prepare the transaction manager to serve operations.
	 * At this time you should detect whether the StorageManager is inconsistent and recover it.
	 */
	public void initAndRecover(StorageManager sm, LogManager lm) {
		latestValues = sm.readStoredTable();
		this.logManager = lm;
		this.storageManager = sm;
		// 开始恢复
		int index = logManager.getLogTruncationOffset() + 4;
		List<ByteBuffer> recordList      = new ArrayList<>();   // 日志记录存储列表
		List<ByteBuffer> groupRecordList = new ArrayList<>();   // 分割日志存储列表
		Map<Long, Integer> txID_tag_map  = new HashMap<>();     // 用于存放每个事务 ID 和 tag 的映射关系
		int len = 10 + 4;
		// 如果日志最后一次写入后的偏移在截断点之后，说明在最后一次事务提交时发生崩溃，需要恢复
		while (logManager.getLogEndOffset() > index) {
			if (index + len > logManager.getLogEndOffset()) { // 如果无法在读取下一条日志的长度，说明已到了最后一条日志
				len = len - 4;
			}
			ByteBuffer record = ByteBuffer.allocate(len);
			record.put(logManager.readLogRecord(index, len));
			long txID = record.getLong(2);
			// 判断日志记录类型，做相应处理
			if (record.get(0) == 0) {
				recordList.add(record);
			} else {
				if (record.get(1) == 0) {
					recordList.clear();
					txID_tag_map.put(txID, index - 4);
				} else {
					for (ByteBuffer bbf : recordList) {
						if (bbf.get(0) == 0 && bbf.get(1) == 1) {  // 如果是分割的 value ，存储并等待合并
							groupRecordList.add(record);
							continue;
						}
						// 开始完整的 key-value 记录的恢复
						long key = bbf.getLong(2);
						bbf.position(10);
						int data_size = groupRecordList.size() * (128 - 14) + bbf.capacity() - bbf.position() - 4;
						int groupRecordIndex = 0;
						byte[] bytes = new byte[data_size];
						for (ByteBuffer group : groupRecordList) {
							group.position(10);
							for (int i = groupRecordIndex; i < groupRecordIndex + 128 - 14; i++) {
								bytes[i] = group.get();
							}
							groupRecordIndex += 128 - 14;
						}
						groupRecordList.clear();
						for (int i = groupRecordIndex; i < data_size; i++) {
							bytes[i] = bbf.get();
						}
						// 恢复持久化
						storageManager.queueWrite(key, txID_tag_map.get(txID), bytes);
						// 恢复事务
						latestValues.put(key, new StorageManager.TaggedValue(txID_tag_map.get(txID), bytes));
					}
				}
			}
			index = index + len;
			len = record.getInt(record.capacity() - 4) + 4;
		}
	}

	/**
	 * Indicates the start of a new transaction. We will guarantee that txID always increases (even across crashes)
	 */
	public void start(long txID) {
		// TODO: Not implemented for non-durable transactions, you should implement this
	}

	/**
	 * Returns the latest committed value for a key by any transaction.
	 */
	public byte[] read(long txID, long key) {
		TaggedValue taggedValue = latestValues.get(key);
		return taggedValue == null ? null : taggedValue.value;
	}

	/**
	 * Indicates a write to the database. Note that such writes should not be visible to read() 
	 * calls until the transaction making the write commits. For simplicity, we will not make reads 
	 * to this same key from txID itself after we make a write to the key. 
	 */
	public void write(long txID, long key, byte[] value) {
		ArrayList<WritesetEntry> writeset = writesets.get(txID);
		if (writeset == null) {
			writeset = new ArrayList<>();
			writesets.put(txID, writeset);
		}
		writeset.add(new WritesetEntry(key, value));
	}
	/**
	 * Commits a transaction, and makes its writes visible to subsequent read operations.\
	 */
	public void commit(long txID) {
		//commit就是提交到latestValues
		ArrayList<WritesetEntry> writeset = writesets.get(txID);
		if (writeset != null) {
			long tag = logManager.getLogEndOffset(); // 记录当前日志的最后偏移，即本次提交事务的起始偏移作为 tag， 即之后的检查点
			// 提交事务开始标识日志记录
			ByteBuffer prepare = ByteBuffer.allocate(14);
			prepare.putInt(10);//int占4个字节
			prepare.put((byte) 1);
			prepare.put((byte) 0);
			prepare.putLong(txID);//long占字节
			logManager.appendLogRecord(prepare.array());

			// 开始提交事务 key-value 记录
			for (WritesetEntry x : writeset) {
				int maxValueLen = 128 - 14;
				int valueIndex = 0;
				while (x.value.length - valueIndex > maxValueLen) {
					ByteBuffer groupLog = ByteBuffer.allocate(128);
					groupLog.putInt(maxValueLen + 10);//4个字节
					groupLog.put((byte) 0);
					groupLog.put((byte) 1);
					groupLog.putLong(x.key);//8个字节
					byte[] bytes = new byte[maxValueLen];//maxvalueLen = 128 - 14
					for (int i = 0; i < maxValueLen; i++) {
						bytes[i] = x.value[valueIndex + i];
					}
					groupLog.put(bytes);
					logManager.appendLogRecord(groupLog.array());
					valueIndex += maxValueLen;
				}
				//先写入redo log  再写入数据
				int data_len = 10 + x.value.length;
				int buffer_len = data_len + 4;
				ByteBuffer completeLog = ByteBuffer.allocate(buffer_len);
				completeLog.putInt(data_len);//4个字节
				completeLog.put((byte) 0);
				completeLog.put((byte) 0);
				completeLog.putLong(x.key);//key是long，占8个字节
				byte[] bytes = new byte[x.value.length - valueIndex];
				for (int i = 0; i < x.value.length - valueIndex; i++) {
					bytes[i] = x.value[valueIndex + i];
				}
				completeLog.put(bytes);
				logManager.appendLogRecord(completeLog.array());
				//  写入持久化
				storageManager.queueWrite(x.key, tag, x.value);
				//  提交事务修改
				latestValues.put(x.key, new TaggedValue(tag, x.value));
			}
			// 提交事务结束标识日志记录，开始和结束都分配14byte
			ByteBuffer commit = ByteBuffer.allocate(14);
			commit.putInt(10);
			commit.put((byte) 1);
			commit.put((byte) 1);
			commit.putLong(txID);
			logManager.appendLogRecord(commit.array());
			writesets.remove(txID);
		}
	}
	/**
	 * Aborts a transaction.
	 */
	public void abort(long txID) {
		writesets.remove(txID);
	}

	/**
	 * The storage manager will call back into this procedure every time a queued write becomes persistent.
	 * These calls are in order of writes to a key and will occur once for every such queued write, unless a crash occurs.
	 */
	public void writePersisted(long key, long persisted_tag, byte[] persisted_value) {
		// 检查是否是有效的事件开始标志
		if (persisted_tag <= logManager.getLogTruncationOffset() || persisted_tag >= logManager.getLogEndOffset())
			return;
		// 逐条检查日志记录，直到找到结束标志日志记录
		int index = (int) persisted_tag + 4;
		int len = 10 + 4;
		ByteBuffer record = ByteBuffer.allocate(len);
		record.put(logManager.readLogRecord(index, len));
		long txID_Prepare = record.getLong(2);
		index = index + len;
		len = record.getInt(record.capacity() - 4) + 4;
		if (record.get(0) == 0)//遇到数据记录，返回
			return;
		while (index < logManager.getLogEndOffset()) {
			if (index + len > logManager.getLogEndOffset())
				len = len - 4;
			record = ByteBuffer.allocate(len);
			record.put(logManager.readLogRecord(index, len));
			if (record.get(0) == 1) {
				if (record.get(1) == 1) {// 1 1为结束
					long txID_commit = record.getLong(2);
					if (txID_commit == txID_Prepare) {
						logManager.setLogTruncationOffset((int) persisted_tag);
						return;
					}
				} else {
					return;
				}
			}
			index = index + len;
			len = record.getInt(record.capacity() - 4) + 4;
		}
	}
}
