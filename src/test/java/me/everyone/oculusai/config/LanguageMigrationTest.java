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

    /** Сообщение, оставшееся ровно таким, каким его выпустил прошлый jar,
     *  обновляется до нового текста. */
    @Test
    void migratesAnUntouchedOldMessage() throws Exception {
        YamlConfiguration lang = yaml("""
                forget:
                  done: "<gray>Сброшен <white>%player%<gray>: буфер <white>%buffer%<gray> -> <white>0%dropped%<gray>. Подозрение начинается заново."
                """);
        YamlConfiguration defaults = yaml("""
                forget:
                  done: "<white>Забыл игрока <accent>%player%"
                """);

        assertEquals(1, Language.migrateMessages(lang, defaults));
        assertEquals("<white>Забыл игрока <accent>%player%", lang.getString("forget.done"));
    }

    /** Переписанное владельцем сервера сообщение миграция не трогает. */
    @Test
    void keepsARewrittenMessage() throws Exception {
        YamlConfiguration lang = yaml("forget:\n  done: \"<red>Готово\"\n");
        YamlConfiguration defaults = yaml("forget:\n  done: \"<white>Forgot player <accent>%player%\"\n");

        assertEquals(0, Language.migrateMessages(lang, defaults));
        assertEquals("<red>Готово", lang.getString("forget.done"));
    }

    /** Строка, равная любому прежнему выпуску из lang/legacy, обновляется;
     *  переписанная и уже актуальная остаются как есть. */
    @Test
    void migratesAnyValueFromAnEarlierRelease() throws Exception {
        YamlConfiguration lang = yaml("""
                npc:
                  cleared: "<gray>Манекены убраны."
                  full: "<red>Свой текст"
                  killed: "<white>Манекен <accent>%value%<white> повержен."
                  limit: 3
                """);
        YamlConfiguration defaults = yaml("""
                npc:
                  cleared: "<white>Манекены убраны."
                  full: "<white>Уже достигнут лимит манекенов."
                  killed: "<white>Манекен <accent>%value%<white> повержен."
                  limit: 3
                """);
        YamlConfiguration legacy = yaml("""
                npc:
                  cleared:
                  - "<gray>Манекены убраны."
                  full:
                  - "<red>Уже достигнут лимит манекенов."
                  killed:
                  - "<gray>Манекен <white>%value%<gray> повержен."
                """);

        assertEquals(1, Language.migrateHistory(lang, defaults, legacy));
        assertEquals("<white>Манекены убраны.", lang.getString("npc.cleared"));
        assertEquals("<red>Свой текст", lang.getString("npc.full"));
        assertEquals(3, lang.getInt("npc.limit"));
    }

    /** Каждый ключ истории есть в текущем файле, а текущее значение в
     *  историю не попало - иначе миграция заменяла бы строку на неё же. */
    @Test
    void bundledHistoryMatchesTheBundledFiles() throws Exception {
        for (String name : new String[] {"en.yml", "ru.yml"}) {
            YamlConfiguration current = resource("lang/" + name);
            YamlConfiguration legacy = resource("lang/legacy/" + name);
            int keys = 0;
            for (String key : legacy.getKeys(true)) {
                if (!legacy.isList(key)) {
                    continue;
                }
                keys++;
                String now = current.getString(key);
                org.junit.jupiter.api.Assertions.assertNotNull(now, name + " has no " + key);
                org.junit.jupiter.api.Assertions.assertFalse(
                        legacy.getStringList(key).contains(now), name + " " + key);
            }
            org.junit.jupiter.api.Assertions.assertTrue(keys > 50, name + ": " + keys + " keys");
        }
    }

    private static YamlConfiguration resource(String path) throws Exception {
        try (java.io.InputStream input = LanguageMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            org.junit.jupiter.api.Assertions.assertNotNull(input, path);
            return yaml(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
