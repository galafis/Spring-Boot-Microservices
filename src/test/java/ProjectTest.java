import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.File;

/**
 * Unit tests for Spring-Boot-Microservices
 * Auto-generated test scaffold — extend with project-specific tests
 */
class ProjectTest {

    @Test
    void testReadmeExists() {
        File readme = new File("README.md");
        assertTrue(readme.exists(), "README.md should exist");
    }

    @Test
    void testLicenseExists() {
        File license = new File("LICENSE");
        assertTrue(license.exists(), "LICENSE should exist");
    }

    @Test
    void testBasicFunctionality() {
        assertEquals(4, 2 + 2, "Basic arithmetic should work");
    }
}
