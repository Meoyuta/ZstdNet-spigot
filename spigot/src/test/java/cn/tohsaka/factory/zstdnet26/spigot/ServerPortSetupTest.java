package cn.tohsaka.factory.zstdnet26.spigot;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ServerPortSetupTest {
    @Test
    void preservesOriginalPublicPortWhenSetupRunsAgain() throws IOException {
        int publicPort = ServerPortSetup.resolvePublicPort(null, 22619, 22620, 22621);
        int backendPort = ServerPortSetup.preferredBackendPort(publicPort, 22621, 22621);

        assertEquals(22619, publicPort);
        assertEquals(22621, backendPort);
    }

    @Test
    void usesExplicitPublicPortOverrideForDriftRepair() throws IOException {
        int publicPort = ServerPortSetup.resolvePublicPort(22619, 22620, 22620, 22621);
        int backendPort = ServerPortSetup.preferredBackendPort(publicPort, 22621, 22621);

        assertEquals(22619, publicPort);
        assertEquals(22621, backendPort);
    }

    @Test
    void fallsBackToCurrentServerPortOnFirstRun() throws IOException {
        assertEquals(22619, ServerPortSetup.resolvePublicPort(null, -1, -1, 22619));
        assertEquals(-1, ServerPortSetup.preferredBackendPort(22619, 22619, -1));
    }

    @Test
    void rejectsInvalidExplicitPublicPort() {
        assertThrows(IOException.class, () -> ServerPortSetup.resolvePublicPort(70000, 22619, 22619, 22619));
    }
}
