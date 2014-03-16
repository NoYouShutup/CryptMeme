package net.i2p.router;

import java.util.concurrent.atomic.AtomicLong;

import net.i2p.data.DataHelper;

/**
 *  Glorified struct to contain basic job stats.
 *  Public for router console only.
 *  For use by the router only. Not to be used by applications or plugins.
 */
public class JobStats {
    private final String _job;
    private final AtomicLong _numRuns = new AtomicLong();
    private volatile long _totalTime;
    private volatile long _maxTime;
    private volatile long _minTime;
    private volatile long _totalPendingTime;
    private volatile long _maxPendingTime;
    private volatile long _minPendingTime;
    
    public JobStats(String name) {
        _job = name;
        _maxTime = -1;
        _minTime = -1;
        _maxPendingTime = -1;
        _minPendingTime = -1;
    }
    
    public void jobRan(long runTime, long lag) {
        _numRuns.incrementAndGet();
        _totalTime += runTime;
        if ( (_maxTime < 0) || (runTime > _maxTime) )
            _maxTime = runTime;
        if ( (_minTime < 0) || (runTime < _minTime) )
            _minTime = runTime;
        _totalPendingTime += lag;
        if ( (_maxPendingTime < 0) || (lag > _maxPendingTime) )
            _maxPendingTime = lag;
        if ( (_minPendingTime < 0) || (lag < _minPendingTime) )
            _minPendingTime = lag;
    }
    
    public String getName() { return _job; }
    public long getRuns() { return _numRuns.get(); }
    public long getTotalTime() { return _totalTime; }
    public long getMaxTime() { return _maxTime; }
    public long getMinTime() { return _minTime; }
    public long getAvgTime() { 
        long numRuns = _numRuns.get();
        if (numRuns > 0) 
            return _totalTime / numRuns; 
        else 
            return 0; 
    }
    public long getTotalPendingTime() { return _totalPendingTime; }
    public long getMaxPendingTime() { return _maxPendingTime; }
    public long getMinPendingTime() { return _minPendingTime; }
    public long getAvgPendingTime() { 
        long numRuns = _numRuns.get();
        if (numRuns > 0) 
            return _totalPendingTime / numRuns; 
        else 
            return 0; 
    }
    
    @Override
    public int hashCode() { return _job.hashCode(); }
    @Override
    public boolean equals(Object obj) {
        if ( (obj != null) && (obj instanceof JobStats) ) {
            JobStats stats = (JobStats)obj;
            return DataHelper.eq(getName(), stats.getName()) &&
                   getRuns() == stats.getRuns() &&
                   getTotalTime() == stats.getTotalTime() &&
                   getMaxTime() == stats.getMaxTime() &&
                   getMinTime() == stats.getMinTime();
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Over ").append(getRuns()).append(" runs, job <b>").append(getName()).append("</b> took ");
        buf.append(getTotalTime()).append("ms (").append(getAvgTime()).append("ms/").append(getMaxTime()).append("ms/");
        buf.append(getMinTime()).append("ms avg/max/min) after a total lag of ");
        buf.append(getTotalPendingTime()).append("ms (").append(getAvgPendingTime()).append("ms/");
        buf.append(getMaxPendingTime()).append("ms/").append(getMinPendingTime()).append("ms avg/max/min)");
        return buf.toString();
    }
}
