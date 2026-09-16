package studio.agent.platform.internalapi;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import studio.agent.platform.security.WorkerTokenGuard;
class ArtifactAuthorizationTest { @Test void binds_kind_to_the_correct_worker(){var tokens=new WorkerTokenGuard("agent","browser");assertDoesNotThrow(()->ArtifactAuthorization.require(tokens,"Bearer agent","HTML"));assertDoesNotThrow(()->ArtifactAuthorization.require(tokens,"Bearer browser","SCREENSHOT"));assertThrows(SecurityException.class,()->ArtifactAuthorization.require(tokens,"Bearer agent","SCREENSHOT"));assertThrows(SecurityException.class,()->ArtifactAuthorization.require(tokens,"Bearer browser","DOC"));} }
