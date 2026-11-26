import com.sun.tools.attach.VirtualMachine;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

class PoolProbe {
    public static void main(String[] args) throws Exception {
        String address;
        var vm = VirtualMachine.attach(args[0]);
        try {
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        try (var connection = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            var server = connection.getMBeanServerConnection();
            var pool = server.queryNames(new ObjectName("com.zaxxer.hikari:type=*"), null).stream()
                    .filter(name -> name.getKeyProperty("type").startsWith("Pool ("))
                    .findFirst().orElseThrow();
            if (args.length == 2 && "--evict-idle".equals(args[1])) {
                server.invoke(pool, "softEvictConnections", new Object[0], new String[0]);
                return;
            }
            var memory = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.MEMORY_MXBEAN_NAME,
                    MemoryMXBean.class);
            var os = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME,
                    com.sun.management.OperatingSystemMXBean.class);
            while (true) {
                System.out.printf("%d\t%s\t%s\t%s\t%d\t%d%n", System.currentTimeMillis(),
                        server.getAttribute(pool, "ActiveConnections"), server.getAttribute(pool, "IdleConnections"),
                        server.getAttribute(pool, "ThreadsAwaitingConnection"), memory.getHeapMemoryUsage().getUsed(),
                        os.getProcessCpuTime());
                Thread.sleep(Duration.ofMillis(50));
            }
        }
    }
}
