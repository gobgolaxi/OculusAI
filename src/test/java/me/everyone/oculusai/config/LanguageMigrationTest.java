package me.everyone.oculusai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LanguageMigrationTest {

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    /** Языковой файл 2.1.0 и старше: секции, число и нетронутые имена. Именно
     *  на нём 2.2.0 и 2.2.1 падали при включении. */
    @Test
    void renamesUntouchedNamesInAFileWithSectionsAndNumbers() throws Exception {
        YamlConfiguration lang = yaml("""
                check:
                  name:
                    aim: "Neuro Aim"
                    wall: "Wall"
                check-settings:
                  aim:
                    name: "<light_purple>Neuro Aim"
                    slot: 13
                """);
        YamlConfiguration defaults = yaml("""
                check:
                  name:
                    aim: "Neuro"
                    wall: "Wall"
                check-settings:
                  aim:
                    name: "<light_purple>Neuro"
                    slot: 13
                """);

        assertEquals(2, Language.migrateAimNames(lang, defaults));
        assertEquals("Neuro", lang.getString("check.name.aim"));
        assertEquals("<light_purple>Neuro", lang.getString("check-settings.aim.name"));
        assertEquals(13, lang.getInt("check-settings.aim.slot"));
    }

    @Test
    void keepsACustomTranslation() throws Exception {
        YamlConfiguration lang = yaml("check:\n  name:\n    aim: \"Нейро-аим\"\n");
        YamlConfiguration defaults = yaml("check:\n  name:\n    aim: \"Neuro\"\n");

        assertEquals(0, Language.migrateAimNames(lang, defaults));
        assertEquals("Нейро-аим", lang.getString("check.name.aim"));
    }
}
