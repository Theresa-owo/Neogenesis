import io.netty.buffer.Unpooled;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetworkSystem;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ChatComponentText;

/**
 * Regression tests for the netty 4.2 migration:
 *  1. NetworkSystem.addLocalEndpoint()  -> binds the LocalServerChannel (crashed pre-fix)
 *  2. NetworkManager.provideLocalClient(addr) -> connects the client LocalChannel
 *  3. PacketBuffer.readStringFromBuffer over a DIRECT buffer (crashed via S3FPacketCustomPayload)
 */
public class LocalChannelSmoke {
    public static void main(String[] args) throws Exception {
        NetworkSystem networkSystem = new NetworkSystem(null);
        java.net.SocketAddress address = networkSystem.addLocalEndpoint();
        System.out.println("[1/3] LocalServerChannel bound on: " + address);

        NetworkManager networkManager = NetworkManager.provideLocalClient(address);
        boolean open = false, local = false;
        for (int i = 0; i < 40 && !(open && local); i++) {
            open = networkManager.isChannelOpen();
            local = networkManager.isLocalChannel();
            if (!(open && local)) Thread.sleep(50);
        }
        System.out.println("[2/3] Client LocalChannel after wait: localChannel=" + local + " open=" + open);
        if (!open || !local) throw new IllegalStateException("local channel did not become active");

        PacketBuffer direct = new PacketBuffer(Unpooled.directBuffer(256));
        direct.writeString("hello neogenesis 你好");
        String readBack = direct.readStringFromBuffer(64);
        if (!"hello neogenesis 你好".equals(readBack)) {
            throw new IllegalStateException("direct buffer string roundtrip failed: " + readBack);
        }
        System.out.println("[3/3] direct-buffer string roundtrip OK: " + readBack);

        networkManager.closeChannel(new ChatComponentText("smoke test done"));
        Thread.sleep(500);
        System.out.println("SMOKE OK");
        System.exit(0);
    }
}
