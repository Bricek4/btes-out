package studio.agent.contracts;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class LoginLocatorTest { @Test void allows_only_declarative_locator_kinds(){assertDoesNotThrow(()->new LoginLocator("role","button","Sign in"));assertThrows(IllegalArgumentException.class,()->new LoginLocator("css",null,"#submit"));} }
