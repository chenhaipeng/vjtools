package com.vip.vjtools.vjtop;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Date;

import com.sun.management.OperatingSystemMXBean;
import com.vip.vjtools.vjtop.util.Formats;
import com.vip.vjtools.vjtop.util.LongObjectHashMap;
import com.vip.vjtools.vjtop.util.LongObjectMap;
import com.vip.vjtools.vjtop.util.Utils;

@SuppressWarnings("restriction")
public class VMDetailView {

	private static final int DEFAULT_WIDTH = 100;
	private static final int MIN_WIDTH = 80;

	public volatile DetailMode mode;
	public volatile int threadLimit = 10;
	public volatile int interval;
	public volatile String threadNameFilter = null;

	private int width;
	private long minDeltaCpuTime;
	private long minDeltaMemory;

	private VMInfo vmInfo;
	private WarningRule warning;

	// 纪录vjtop进程本身的消耗
	private boolean isDebug = false;
	private long lastCpu = 0;

	private boolean shouldExit = false;
	private boolean firstTime = true;
	public volatile boolean displayCommandHints = false;
	public volatile boolean collectingData = true;

	private LongObjectMap<Long> lastThreadCpuTotalTimes = new LongObjectHashMap<Long>();
	private LongObjectMap<Long> lastThreadSysCpuTotalTimes = new LongObjectHashMap<Long>();
	private LongObjectMap<Long> lastThreadMemoryTotalBytes = new LongObjectHashMap<Long>();

	private long[] topTidArray;

	public VMDetailView(VMInfo vmInfo, DetailMode mode, Integer width, Integer interval) throws Exception {
		this.vmInfo = vmInfo;
		this.warning = vmInfo.warningRule;
		this.mode = mode;
		this.interval = interval;
		setWidth(width);
	}

	public void printView() throws Exception {
		long iterationStartTime = 0;
		long iterationStartCpu = 0;
		if (isDebug) {
			iterationStartTime = System.currentTimeMillis();
			iterationStartCpu = ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
					.getProcessCpuTime();
		}

		vmInfo.update();

		if (!checkState()) {
			return;
		}

		// 打印进程级别内容
		printJvmInfo();

		// JMX更新失败，不打印后续一定需要JMX获取的数据
		if (!vmInfo.isJmxStateOk()) {
			System.out.print("\n " + Formats.RED_ANSI[0] + "ERROR: Could not fetch data via JMX");
			if (!vmInfo.currentGcCause.equals("No GC")) {
				System.out.println(" - Process is doing GC, cause is " + vmInfo.currentGcCause + Formats.RED_ANSI[1]);
			} else {
				System.out.println(" - Process terminated?" + Formats.RED_ANSI[1]);
			}

			return;
		}

		// 打印线程级别内容
		try {
			if (mode.isCpuMode) {
				printTopCpuThreads(mode);
			} else {
				printTopMemoryThreads(mode);
			}
		} catch (Exception e) {
			System.out.println("\n" + Formats.RED_ANSI[0]
					+ "ERROR: Exception happen when fetch thread information via JMX" + Formats.RED_ANSI[1]);
		}

		if (isDebug) {
			// 打印vjtop自身消耗
			printIterationCost(iterationStartTime, iterationStartCpu);
		}

		if (displayCommandHints) {
			System.out.print(" Input command (h for help):");
		}
	}

	private boolean checkState() {
		if (vmInfo.state != VMInfo.VMInfoState.ATTACHED && vmInfo.state != VMInfo.VMInfoState.ATTACHED_UPDATE_ERROR) {
			System.out.println(
					"\n" + Formats.RED_ANSI[0] + "ERROR: Could not attach to process, exit now." + Formats.RED_ANSI[1]);
			exit();
			return false;
		}
		return true;
	}

	private void printJvmInfo() {
		System.out.printf(" PID: %s - %8tT JVM: %s USER: %s UPTIME: %s%n", vmInfo.pid, new Date(), vmInfo.jvmVersion,
				vmInfo.osUser, Formats.toTimeUnit(vmInfo.upTimeMills.current));

		String[] cpuLoadAnsi = Formats.colorAnsi(vmInfo.cpuLoad, warning.cpu);

		System.out.printf(" PROCESS: %5.2f%% cpu(%s%5.2f%%%s of %d core)", vmInfo.singleCoreCpuLoad, cpuLoadAnsi[0],
				vmInfo.cpuLoad, cpuLoadAnsi[1], vmInfo.processors);

		if (vmInfo.isLinux) {
			System.out.printf(", %s thread%n", Formats.toColor(vmInfo.osThreads, warning.thread));

			System.out.printf(" MEMORY: %s rss, %s peak, %s swap |", Formats.toMB(vmInfo.rss),
					Formats.toMB(vmInfo.peakRss), Formats.toMBWithColor(vmInfo.swap, warning.swap));

			if (vmInfo.ioDataSupport) {
				System.out.printf(" DISK: %sB read, %sB write",
						Formats.toSizeUnitWithColor(vmInfo.readBytes.ratePerSecond, warning.io),
						Formats.toSizeUnitWithColor(vmInfo.writeBytes.ratePerSecond, warning.io));
			}
		}
		System.out.println();

		System.out.printf(" THREAD: %s live, %d daemon, %s peak, %s new",
				Formats.toColor(vmInfo.threadActive, warning.thread), vmInfo.threadDaemon, vmInfo.threadPeak,
				Formats.toColor(vmInfo.threadNew.delta, warning.newThread));

		System.out.printf(" | CLASS: %s loaded, %d unloaded, %s new%n",
				Formats.toColor(vmInfo.classLoaded.current, warning.loadClass), vmInfo.classUnLoaded,
				Formats.toColor(vmInfo.classLoaded.delta, warning.newClass));

		System.out.printf(" HEAP: %s eden, %s sur, %s old%n", Formats.formatUsage(vmInfo.eden),
				Formats.formatUsage(vmInfo.sur), Formats.formatUsageWithColor(vmInfo.old, warning.old));

		System.out.printf(" NON-HEAP: %s %s, %s codeCache", Formats.formatUsageWithColor(vmInfo.perm, warning.perm),
				vmInfo.permGenName, Formats.formatUsageWithColor(vmInfo.codeCache, warning.codeCache));
		if (vmInfo.jvmMajorVersion >= 8) {
			System.out.printf(", %s ccs", Formats.formatUsage(vmInfo.ccs));
		}
		System.out.println("");

		System.out.printf(" OFF-HEAP: %s/%s direct(max=%s), %s/%s map(count=%d), %s threadStack%n",
				Formats.toMB(vmInfo.direct.used), Formats.toMB(vmInfo.direct.committed),
				Formats.toMB(vmInfo.direct.max), Formats.toMB(vmInfo.map.used), Formats.toMB(vmInfo.map.committed),
				vmInfo.map.max, Formats.toMB(vmInfo.threadStackSize * vmInfo.threadActive));

		long ygcCount = vmInfo.ygcCount.delta;
		long ygcTime = vmInfo.ygcTimeMills.delta;
		long avgYgcTime = ygcCount == 0 ? 0 : ygcTime / ygcCount;
		long fgcCount = vmInfo.fullgcCount.delta;
		System.out.printf(" GC: %s/%sms/%sms ygc, %s/%dms fgc", Formats.toColor(ygcCount, warning.ygcCount),
				Formats.toColor(ygcTime, warning.ygcTime), Formats.toColor(avgYgcTime, warning.ygcAvgTime),
				Formats.toColor(fgcCount, warning.fullgcCount), vmInfo.fullgcTimeMills.delta);

		if (vmInfo.perfDataSupport) {
			System.out.printf(" | SAFE-POINT: %s count, %sms time, %dms syncTime",
					Formats.toColor(vmInfo.safepointCount.delta, warning.safepointCount),
					Formats.toColor(vmInfo.safepointTimeMills.delta, warning.safepointTime),
					vmInfo.safepointSyncTimeMills.delta);
		}
		System.out.println("");
	}

	private void printTopCpuThreads(DetailMode mode) throws IOException {
		if (!vmInfo.threadCpuTimeSupported) {
			System.out.printf("%n -Thread CPU telemetries are not available on the monitored jvm/platform-%n");
			return;
		}

		long noteableThreads = 0;

		long tids[] = vmInfo.getThreadMXBean().getAllThreadIds();
		int mapSize = tids.length * 2;
		LongObjectMap<Long> threadCpuTotalTimes = new LongObjectHashMap<Long>(mapSize);
		LongObjectMap<Long> threadCpuDeltaTimes = new LongObjectHashMap<>(mapSize);
		LongObjectMap<Long> threadSysCpuTotalTimes = new LongObjectHashMap<>(mapSize);
		LongObjectMap<Long> threadSysCpuDeltaTimes = new LongObjectHashMap<>(mapSize);

		// 批量获取CPU times，性能大幅提高。
		// 两次获取之间有间隔，在低流量下可能造成负数
		long[] threadCpuTotalTimeArray = vmInfo.getThreadMXBean().getThreadCpuTime(tids);
		long[] threadUserCpuTotalTimeArray = vmInfo.getThreadMXBean().getThreadUserTime(tids);

		long deltaAllThreadCpu = 0;
		long deltaAllThreadSysCpu = 0;

		// 过滤CPU占用太少的线程，每秒0.05%CPU (0.5ms cpu time)
		minDeltaCpuTime = (vmInfo.upTimeMills.delta * Utils.NANOS_TO_MILLS / 2000);

		// 计算本次CPU Time
		// 此算法第一次不会显示任何数据，保证每次显示都只显示区间内数据
		for (int i = 0; i < tids.length; i++) {
			long tid = tids[i];
			Long threadCpuTotalTime = threadCpuTotalTimeArray[i];
			threadCpuTotalTimes.put(tid, threadCpuTotalTime);

			Long lastTime = lastThreadCpuTotalTimes.get(tid);
			if (lastTime != null) {
				Long deltaThreadCpuTime = threadCpuTotalTime - lastTime;
				if (deltaThreadCpuTime >= minDeltaCpuTime) {
					threadCpuDeltaTimes.put(tid, deltaThreadCpuTime);
					deltaAllThreadCpu += deltaThreadCpuTime;
				}
			}
		}

		// 计算本次SYSCPU Time
		for (int i = 0; i < tids.length; i++) {
			long tid = tids[i];
			// 因为totalTime 与 userTime 的获取时间有先后，实际sys接近0时，后取的userTime可能比前一时刻的totalTime高，计算出来的sysTime可为负数
			Long threadSysCpuTotalTime = Math.max(0, threadCpuTotalTimeArray[i] - threadUserCpuTotalTimeArray[i]);
			threadSysCpuTotalTimes.put(tid, threadSysCpuTotalTime);

			Long lastTime = lastThreadSysCpuTotalTimes.get(tid);
			if (lastTime != null) {
				Long deltaThreadSysCpuTime = Math.max(0, threadSysCpuTotalTime - lastTime);
				if (deltaThreadSysCpuTime >= minDeltaCpuTime) {
					threadSysCpuDeltaTimes.put(tid, deltaThreadSysCpuTime);
					deltaAllThreadSysCpu += deltaThreadSysCpuTime;
				}
			}
		}

		// 第一次无数据时跳过
		if (lastThreadCpuTotalTimes.isEmpty()) {
			lastThreadCpuTotalTimes = threadCpuTotalTimes;
			lastThreadSysCpuTotalTimes = threadSysCpuTotalTimes;
			printWelcome();
			return;
		}

		collectingData = false;

		// 打印线程view的页头
		String titleFormat = " %6s %-" + getThreadNameWidth() + "s %10s %6s %6s %6s %6s%n";
		String dataFormat = " %6d %-" + getThreadNameWidth() + "s %10s %s%5.2f%%%s %s%5.2f%%%s %5.2f%% %5.2f%%%n";
		System.out.printf("%n%n" + titleFormat, "TID", "NAME  ", "STATE", "CPU", "SYSCPU", " TOTAL", "TOLSYS");

		// 按不同类型排序,过滤
		if (mode == DetailMode.cpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadCpuDeltaTimes, threadLimit);
			noteableThreads = threadCpuDeltaTimes.size();
		} else if (mode == DetailMode.syscpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadSysCpuDeltaTimes, threadLimit);
			noteableThreads = threadSysCpuDeltaTimes.size();
		} else if (mode == DetailMode.totalcpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadCpuTotalTimes, threadLimit);
			noteableThreads = threadCpuTotalTimes.size();
		} else if (mode == DetailMode.totalsyscpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadSysCpuTotalTimes, threadLimit);
			noteableThreads = threadSysCpuTotalTimes.size();
		} else {
			throw new RuntimeException("unkown mode:" + mode);
		}

		if (noteableThreads == 0) {
			System.out.printf("%n -Every thread use cpu lower than 0.05%%-%n");
		}

		// 获得threadInfo
		ThreadInfo[] threadInfos = vmInfo.getThreadMXBean().getThreadInfo(topTidArray);

		// 打印线程Detail
		for (ThreadInfo info : threadInfos) {
			if (info == null) {
				continue;
			}
			Long tid = info.getThreadId();
			String threadName = Formats.shortName(info.getThreadName(), getThreadNameWidth(), 20);
			// 过滤threadName
			if (threadNameFilter != null && !threadName.toLowerCase().contains(threadNameFilter)) {
				continue;
			}
			// 刷新间隔里，所使用的单核CPU比例
			double cpu = Utils.calcLoad(threadCpuDeltaTimes.get(tid), vmInfo.upTimeMills.delta, Utils.NANOS_TO_MILLS);
			String[] cpuAnsi = Formats.colorAnsi(cpu, warning.cpu);

			double syscpu = Utils.calcLoad(threadSysCpuDeltaTimes.get(tid), vmInfo.upTimeMills.delta,
					Utils.NANOS_TO_MILLS);
			String[] syscpuAnsi = Formats.colorAnsi(syscpu, warning.syscpu);

			// 在进程所有消耗的CPU里，本线程的比例
			double totalcpuPercent = Utils.calcLoad(threadCpuTotalTimes.get(tid), vmInfo.cpuTimeNanos.current, 1);

			double totalsysPercent = Utils.calcLoad(threadSysCpuTotalTimes.get(tid), vmInfo.cpuTimeNanos.current, 1);

			System.out.printf(dataFormat, tid, threadName, Formats.leftStr(info.getThreadState().toString(), 10),
					cpuAnsi[0], cpu, cpuAnsi[1], syscpuAnsi[0], syscpu, syscpuAnsi[1], totalcpuPercent,
					totalsysPercent);

		}

		// 打印线程汇总
		double deltaAllThreadCpuLoad = Utils.calcLoad(deltaAllThreadCpu / Utils.NANOS_TO_MILLS,
				vmInfo.upTimeMills.delta);
		double deltaAllThreadSysCpuLoad = Utils.calcLoad(deltaAllThreadSysCpu / Utils.NANOS_TO_MILLS,
				vmInfo.upTimeMills.delta);

		System.out.printf("%n Total  : %5.2f%% cpu(user=%5.2f%%, sys=%5.2f%%) by %d active threads(which cpu>0.05%%)%n",
				deltaAllThreadCpuLoad, deltaAllThreadCpuLoad - deltaAllThreadSysCpuLoad, deltaAllThreadSysCpuLoad,
				noteableThreads);

		System.out.printf(" Setting: top %d threads order by %s%s, flush every %ds%n", threadLimit,
				mode.toString().toUpperCase(), threadNameFilter == null ? "" : " filter by " + threadNameFilter,
				interval);

		lastThreadCpuTotalTimes = threadCpuTotalTimes;
		lastThreadSysCpuTotalTimes = threadSysCpuTotalTimes;
	}

	private void printTopMemoryThreads(DetailMode mode) throws IOException {

		if (!vmInfo.threadMemoryAllocatedSupported) {
			System.out.printf(
					"%n -Thread Memory Allocated telemetries are not available on the monitored jvm/platform-%n");
			return;
		}

		long tids[] = vmInfo.getThreadMXBean().getAllThreadIds();
		int mapSize = tids.length * 2;
		LongObjectMap<Long> threadMemoryTotalBytesMap = new LongObjectHashMap<Long>(mapSize);
		LongObjectMap<Long> threadMemoryDeltaBytesMap = new LongObjectHashMap<Long>(mapSize);

		long totalDeltaBytes = 0;
		long totalBytes = 0;

		long noteableThreads = 0;

		// 批量获取内存分配
		long[] threadMemoryTotalBytesArray = vmInfo.getThreadMXBean().getThreadAllocatedBytes(tids);

		// 过滤太少的线程，每秒小于1k
		minDeltaMemory = vmInfo.upTimeMills.delta * 1024 / 1000;

		// 此算法第一次不会显示任何数据，保证每次显示都只显示区间内数据
		for (int i = 0; i < tids.length; i++) {
			long tid = tids[i];
			Long threadMemoryTotalBytes = threadMemoryTotalBytesArray[i];
			threadMemoryTotalBytesMap.put(tid, threadMemoryTotalBytes);
			totalBytes += threadMemoryTotalBytes;

			Long threadMemoryDeltaBytes = 0L;
			Long lastBytes = lastThreadMemoryTotalBytes.get(tid);

			if (lastBytes != null) {
				threadMemoryDeltaBytes = threadMemoryTotalBytes - lastBytes;
				if (threadMemoryDeltaBytes >= minDeltaMemory) {
					threadMemoryDeltaBytesMap.put(tid, threadMemoryDeltaBytes);
					totalDeltaBytes += threadMemoryDeltaBytes;
				}
			}
		}

		// 第一次无数据跳过
		if (lastThreadMemoryTotalBytes.size() == 0) {
			lastThreadMemoryTotalBytes = threadMemoryTotalBytesMap;
			printWelcome();
			return;
		}

		collectingData = false;

		// 打印线程View的页头
		String titleFormat = " %6s %-" + getThreadNameWidth() + "s %10s %14s %18s%n";
		String dataFormat = " %6d %-" + getThreadNameWidth() + "s %10s %5s/s(%5.2f%%) %10s(%5.2f%%)%n";
		System.out.printf("%n%n" + titleFormat, "TID", "NAME  ", "STATE", "MEMORY", "TOTAL-ALLOCATED");

		// 线程排序
		long[] topTidArray;
		if (mode == DetailMode.memory) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadMemoryDeltaBytesMap, threadLimit);
			noteableThreads = threadMemoryDeltaBytesMap.size();
		} else {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadMemoryTotalBytesMap, threadLimit);
			noteableThreads = threadMemoryTotalBytesMap.size();
		}

		if (noteableThreads == 0) {
			System.out.printf("%n -Every thread allocate memory slower than 1k/s-%n");
		}

		ThreadInfo[] threadInfos = vmInfo.getThreadMXBean().getThreadInfo(topTidArray);

		// 打印线程Detail
		for (ThreadInfo info : threadInfos) {
			if (info == null) {
				continue;
			}
			Long tid = info.getThreadId();
			String threadName = Formats.shortName(info.getThreadName(), getThreadNameWidth(), 12);

			// 过滤threadName
			if (threadNameFilter != null && !threadName.toLowerCase().contains(threadNameFilter)) {
				continue;
			}

			Long threadDelta = threadMemoryDeltaBytesMap.get(tid);
			long allocationRate = threadDelta == null ? 0 : (threadDelta * 1000) / vmInfo.upTimeMills.delta;
			System.out.printf(dataFormat, tid, threadName, Formats.leftStr(info.getThreadState().toString(), 10),
					Formats.toFixLengthSizeUnit(allocationRate),
					getMemoryUtilization(threadMemoryDeltaBytesMap.get(tid), totalDeltaBytes),
					Formats.toFixLengthSizeUnit(threadMemoryTotalBytesMap.get(tid)),
					getMemoryUtilization(threadMemoryTotalBytesMap.get(tid), totalBytes));
		}

		// 打印线程汇总信息，这里因为最后单位是精确到秒，所以bytes除以毫秒以后要乘以1000才是按秒统计
		System.out.printf("%n Total  : %5s/s memory allocated by %d active threads(which >1k/s)%n",
				Formats.toFixLengthSizeUnit((totalDeltaBytes * 1000) / vmInfo.upTimeMills.delta), noteableThreads);

		System.out.printf(" Setting: top %d threads order by %s%s, flush every %ds%n", threadLimit,
				mode.toString().toUpperCase(), threadNameFilter == null ? "" : " filter by " + threadNameFilter,
				interval);

		lastThreadMemoryTotalBytes = threadMemoryTotalBytesMap;
	}

	public void printIterationCost(long iterationStartTime, long iterationStartCpu) {
		long currentCpu = ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
		long deltaIterationTime = System.currentTimeMillis() - iterationStartTime;

		long deltaIterationCpuTime = (currentCpu - iterationStartCpu) / Utils.NANOS_TO_MILLS;
		long deltaOtherCpuTime = (iterationStartCpu - lastCpu) / Utils.NANOS_TO_MILLS;
		long deltaTotalCpuTime = deltaIterationCpuTime + deltaOtherCpuTime;
		lastCpu = currentCpu;

		System.out.printf(" Cost %5.2f%% cpu in %dms, other is %dms, total is %dms%n",
				deltaIterationCpuTime * 100d / deltaIterationTime, deltaIterationTime, deltaOtherCpuTime,
				deltaTotalCpuTime);
	}

	private void printWelcome() {
		if (firstTime) {
			if (!vmInfo.isLinux) {
				System.out.printf("%n OS isn't linux, Process's MEMORY, THREAD, DISK data will be skipped.%n");
			}

			if (!vmInfo.ioDataSupport) {
				System.out.printf("%n /proc/%s/io is not readable, Process's DISK data will be skipped.%n", vmInfo.pid);
			}

			if (!vmInfo.perfDataSupport) {
				System.out.printf("%n Perfdata doesn't support, SAFE-POINT data will be skipped.%n");
			}

			System.out.printf("%n VMARGS: %s%n%n", vmInfo.vmArgs);

			firstTime = false;

		}
		System.out.printf("%n Collecting data, please wait ......%n%n");
		collectingData = true;
	}

	/**
	 * 打印单条线程的stack strace，不造成停顿
	 */
	public void printStack(long tid) throws IOException {
		System.out.println("\n Stack trace of thread " + tid + ":");

		ThreadInfo info = vmInfo.getThreadMXBean().getThreadInfo(tid, 20);
		if (info == null) {
			System.err.println(" TID not exist:" + tid);
			return;
		}
		StackTraceElement[] trace = info.getStackTrace();
		System.out.println(" " + info.getThreadId() + ": \"" + info.getThreadName() + "\"\n   java.lang.Thread.State: "
				+ info.getThreadState().toString());
		for (StackTraceElement traceElement : trace) {
			System.out.println("\tat " + traceElement);
		}
		System.out.flush();
	}

	/**
	 * 打印单条线程的stack strace，不造成停顿
	 */
	public void printTopStack() throws IOException {
		System.out.println("\n Stack trace of top " + threadLimit + " threads:");

		ThreadInfo[] infos = vmInfo.getThreadMXBean().getThreadInfo(topTidArray, 20);
		for (ThreadInfo info : infos) {
			if (info == null) {
				continue;
			}
			StackTraceElement[] trace = info.getStackTrace();
			System.out.println(" " + info.getThreadId() + ": \"" + info.getThreadName()
					+ "\"\n   java.lang.Thread.State: " + info.getThreadState().toString());
			for (StackTraceElement traceElement : trace) {
				System.out.println("\tat " + traceElement);
			}
		}
		System.out.flush();
	}

	/**
	 * 打印所有线程，只获取名称不获取stack，不造成停顿
	 */
	public void printAllThreads() throws IOException {
		System.out.println("\n Thread Id and name for all live threads:");

		long tids[] = vmInfo.getThreadMXBean().getAllThreadIds();
		ThreadInfo[] threadInfos = vmInfo.getThreadMXBean().getThreadInfo(tids);
		for (ThreadInfo info : threadInfos) {
			if (info == null) {
				continue;
			}

			String threadName = info.getThreadName();
			if (threadNameFilter != null && !threadName.toLowerCase().contains(threadNameFilter)) {
				continue;
			}
			System.out.println(
					" " + info.getThreadId() + "\t: \"" + threadName + "\" (" + info.getThreadState().toString() + ")");
		}

		if (threadNameFilter != null) {
			System.out.println(" Thread name filter is:" + threadNameFilter);
		}
		System.out.flush();
	}

	public void cleanupThreadsHistory() {
		this.lastThreadCpuTotalTimes.clear();
		this.lastThreadSysCpuTotalTimes.clear();
		this.lastThreadMemoryTotalBytes.clear();
	}

	private static double getMemoryUtilization(Long threadBytes, long totalBytes) {
		if (threadBytes == null || totalBytes == 0) {
			return 0;
		}

		return (threadBytes * 100d) / totalBytes;// 这里因为最后单位是百分比%，所以bytes除以totalBytes以后要乘以100，才可以再加上单位%
	}

	public boolean shouldExit() {
		return shouldExit;
	}

	/**
	 * Requests the disposal of this view - it should be called again.
	 */
	public void exit() {
		shouldExit = true;
	}

	private void setWidth(Integer width) {
		if (width == null) {
			this.width = DEFAULT_WIDTH;
		} else if (width < MIN_WIDTH) {
			this.width = MIN_WIDTH;
		} else {
			this.width = width;
		}
	}

	private int getThreadNameWidth() {
		return this.width - 48;
	}


	public enum DetailMode {
		cpu(true), totalcpu(true), syscpu(true), totalsyscpu(true), memory(false), totalmemory(false);

		public boolean isCpuMode;

		private DetailMode(boolean isCpuMode) {
			this.isCpuMode = isCpuMode;
		}

		public static DetailMode parse(String mode) {
			switch (mode) {
				case "1":
					return cpu;
				case "2":
					return syscpu;
				case "3":
					return totalcpu;
				case "4":
					return totalsyscpu;
				case "5":
					return memory;
				case "6":
					return totalmemory;
				default:
					return null;
			}
		}
	}
}
