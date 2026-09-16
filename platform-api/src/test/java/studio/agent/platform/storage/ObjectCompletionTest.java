package studio.agent.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ObjectCompletionTest {
  @Test
  void accepts_only_the_reserved_size_and_checksum() {
    var reservation = new ObjectReservation("objects/a", 5, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    assertDoesNotThrow(() -> reservation.verify(5, "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824"));
    assertThrows(IllegalArgumentException.class, () -> reservation.verify(4, reservation.sha256()));
    assertThrows(IllegalArgumentException.class, () -> reservation.verify(5, "0".repeat(64)));
  }
}
