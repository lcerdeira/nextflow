package nextflow.daemon
import java.lang.management.ManagementFactory

import com.sun.management.OperatingSystemMXBean
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.executor.IgBaseTask
import nextflow.file.FileHelper
import nextflow.util.MemoryUnit
import org.apache.ignite.Ignition
import org.apache.ignite.logger.slf4j.Slf4jLogger
import org.apache.ignite.spi.IgniteSpiAdapter
import org.apache.ignite.spi.IgniteSpiConsistencyChecked
import org.apache.ignite.spi.IgniteSpiException
import org.apache.ignite.spi.IgniteSpiMultipleInstancesSupport
import org.apache.ignite.spi.collision.CollisionContext
import org.apache.ignite.spi.collision.CollisionExternalListener
import org.apache.ignite.spi.collision.CollisionJobContext
import org.apache.ignite.spi.collision.CollisionSpi
import org.apache.ignite.spi.collision.jobstealing.JobStealingCollisionSpi
import org.jetbrains.annotations.Nullable

/**
 * Extends stock {@link CustomStealingCollisionSpi} adding the ability to manage job requested resources
 * (cpus, memory, storage, etc)
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
@IgniteSpiMultipleInstancesSupport(true)
@IgniteSpiConsistencyChecked(optional = true)
class CustomStealingCollisionSpi extends IgniteSpiAdapter implements CollisionSpi, CustomStealingCollisionSpiMBean {

    private String fHostName

    private int fAvailCpus

    private MemoryUnit fAvailMemory

    private JobStealingCollisionSpi delegate

    private String fRole

    private OperatingSystemMXBean fBean

    private boolean resourcesLogged

    private int activeJobs

    private int waitingJobs

    private MemoryUnit freeMemory

    private int freeCpus

    /**
     * The max number of *generic* jobs
     */
    private static final int MAX_RUNNING_JOBS = 10

    CustomStealingCollisionSpi() {
        delegate = new JobStealingCollisionSpi()
        delegate.setActiveJobsThreshold(0)
        def field = JobStealingCollisionSpi.getDeclaredField('log')
        field.setAccessible(true)
        field.set(delegate, new Slf4jLogger().getLogger(JobStealingCollisionSpi))
    }

    private OperatingSystemMXBean getSystemMXBean() {
        if( !fBean ) {
            fBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()
        }
        return fBean
    }

    /**
     * @return The node actual hostname
     */
    @Override
    String getHostName() {
        if( fHostName ) {
            return fHostName
        }

        fHostName = System.getenv('HOSTNAME') ?: 'localhost'
    }

    /**
     * @return The number of CPUs available
     */
    @Override
    int getAvailCpus() {
        if( fAvailCpus ) {
            return fAvailCpus
        }

        fAvailCpus = getSystemMXBean().getAvailableProcessors()
        if( getRole() == IgGridFactory.ROLE_MASTER ) {
            // reserve some CPUs for nextflow/ignite scheduling activity on the master node
            if( fAvailCpus > 8 ) {
                fAvailCpus -= 2
            }
            else if( fAvailCpus > 3 ) {
                fAvailCpus -= 1
            }
        }
        return fAvailCpus
    }

    /**
     * @return The total memory available
     */
    @Override
    MemoryUnit getAvailMemory() {
        if( fAvailMemory ) {
            return fAvailMemory
        }

        fAvailMemory = new MemoryUnit(getSystemMXBean().getTotalPhysicalMemorySize())
    }

    /**
     * @return The {@link IgGridFactory#NODE_ROLE} attribute, either MASTER or WORKER
     */
    private String getRole() {
        if( fRole ) {
            return fRole
        }

        fRole = Ignition.ignite(IgGridFactory.GRID_NAME).cluster().localNode().attribute(IgGridFactory.NODE_ROLE)
        log.trace "Local node role `$fRole`"
        return fRole
    }

    /*
     * Log the node computing resources just one time
     */
    private logResources() {
        if( !resourcesLogged ) {
            resourcesLogged = true
            log.debug "Computing resources for node: `$hostName` [${getRole()}] > cpus: ${availCpus}; mem: ${availMemory}; free disk: ${freeDisk} (${FileHelper.getLocalTempPath()})"
        }
    }

    /**
     * @return The actual free space in the node local storage
     */
    private MemoryUnit getFreeDisk() {
        final free = FileHelper.getLocalTempPath().toFile().getFreeSpace()
        new MemoryUnit(free)
    }

    /**
     * Implements the custom job collision strategy
     *
     * @param ctx The {@link CollisionContext} instance
     */
    @Override
    void onCollision(CollisionContext ctx) {

        logResources()

        freeMemory = new MemoryUnit(getSystemMXBean().freePhysicalMemorySize)
        activeJobs = ctx.activeJobs().size()
        waitingJobs = ctx.waitingJobs().size()

        /*
         * get the count of used cpus by tasks and generic jobs
         */
        int busyCpus = 0
        int runningJobs = 0
        ctx .activeJobs() .each { jobCtx ->

            if( jobCtx.job instanceof IgBaseTask ) {
                // count the number of used cpus
                final task = (IgBaseTask)jobCtx.job
                busyCpus += task.resources.cpus
            }
            else {
                // count the number of *generic* jobs (not IgBaseTask) running
                runningJobs ++
            }

        }

        // find out the actual free cpus
        freeCpus = availCpus>busyCpus ? availCpus-busyCpus : 0

        if( log.isTraceEnabled() ) {
            log.trace "Node `$hostName` resources > cpus: $availCpus ($freeCpus) - mem: $availMemory ($freeMemory) - active: $activeJobs - waiting: $waitingJobs"
        }

        // activate waiting jobs that match avail resources
        ctx .waitingJobs() .each { jobCtx ->

            if( jobCtx.job instanceof IgBaseTask ) {
                activateTask(jobCtx, ctx)
            }
            else if( runningJobs < MAX_RUNNING_JOBS && jobCtx.activate() ) {
                runningJobs ++
            }

        }

        // fallback on the job stealing strategy
        delegate.onCollision(ctx)
    }

    /**
     * Activate a pending task if resource constraints are satisfied
     *
     * @param jobCtx The job context
     * @param ctx The collision context
     */
    private void activateTask( CollisionJobContext jobCtx, CollisionContext ctx ) {
        final task = (IgBaseTask)jobCtx.job

        if( task.resources.cpus > availCpus ) {
            log.debug "Task cancelled > $task -- Not enough cpus - requested: ${task.resources.cpus}; avail: ${availCpus}"
            jobCtx.cancel()
        }

        if( task.resources.memory > availMemory ) {
            log.debug "Task cancelled > $task -- Not enough memory - requested: ${task.resources.memory}; avail: ${availMemory}"
            jobCtx.cancel()
        }

        if( task.resources.disk > freeDisk ) {
            log.debug "Task cancelled > $task -- Not enough disk storage - requested: ${task.resources.disk}; avail: ${freeDisk}"
            jobCtx.cancel()
        }

        if( task.resources.cpus > freeCpus ) return
        if( task.resources.memory > freeMemory ) return

        if( jobCtx.activate() ) {
            freeCpus -= task.resources.cpus
            freeMemory -= task.resources.memory
            if( log.isTraceEnabled() )
                log.trace "Activated task > $task -- Pending; ${ctx.waitingJobs().size()} (was: ${waitingJobs}) - active: ${ctx.activeJobs().size()} (was: $activeJobs)"
        }
        else if(log.isTraceEnabled())  {
            log.trace "Failed to activate task > $task"
        }
    }

    @Override
    void setExternalCollisionListener(@Nullable CollisionExternalListener listener) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override
    void spiStart(String gridName) throws IgniteSpiException {

        // Start SPI start stopwatch.
        startStopwatch();
        registerMBean(gridName, this, CustomStealingCollisionSpiMBean.class);

        log.debug(startInfo())
    }

    /** {@inheritDoc} */
    @Override
    void spiStop() throws IgniteSpiException {
        unregisterMBean();
        log.debug(stopInfo())
    }

}
