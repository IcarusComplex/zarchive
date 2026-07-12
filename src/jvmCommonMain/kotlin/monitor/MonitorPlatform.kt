package monitor

// true on desktop (the monitor is a simple in-process coroutine loop that lives exactly as long
// as the app window is open); false on Android, where WorkManager is the sole execution path
// (see MonitorWorker/MonitorScheduler in androidMain) so checks keep running while backgrounded
// or after the process is killed.
expect val runsMonitorLoopInProcess: Boolean
