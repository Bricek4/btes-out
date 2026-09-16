package studio.agent.contracts;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class LoginLocatorTest { @Test void allowsOnlyDeclarativeLocatorKinds(){assertDoesNotThrow(()->new LoginLocator("role","button","Sign in"));assertThrows(IllegalArgumentException.class,()->new LoginLocator("css",null,"#submit"));} }
