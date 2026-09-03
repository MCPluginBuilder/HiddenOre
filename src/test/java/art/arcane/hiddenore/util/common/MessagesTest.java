package art.arcane.hiddenore.util.common;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.RemoteLanguageCatalog;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MessagesTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();
  @Test
  public void editorPersistsEnglishMessagesAndPreservesGlobalOverrides() throws Exception {
    Path data = temporaryFolder.newFolder().toPath();
    Path languages = Files.createDirectories(data.resolve("languages"));
    Files.writeString(data.resolve("language.yml"), "prefix: ''\n");
    Messages messages = new Messages(null, languages);
    messages.reload(new YamlConfiguration(), "language.yml", "en_US");
    PluginLanguageEditor.Options editor = messages.editorOptions();
    LocalizationSnapshot original = editor.loader().load("en_US");
    TextValue replacement = new TextValue("Edited permission message");
    editor.writer().write(new PluginLanguageEditor.Edit("en_US", Messages.NO_PERMISSION.id(),
        original.value(Messages.NO_PERMISSION), replacement));
    TextValue editedReload = new TextValue("Reload complete");
    editor.writer().write(new PluginLanguageEditor.Edit("en_US", Messages.RELOADED.id(),
        original.value(Messages.RELOADED), editedReload));

    assertEquals(replacement, messages.defaultSnapshot().value(Messages.NO_PERMISSION));
    LocalizationSnapshot reloaded = editor.loader().load("en_US");
    assertEquals(replacement, reloaded.value(Messages.NO_PERMISSION));
    assertEquals(editedReload, reloaded.value(Messages.RELOADED));
    assertEquals("prefix: ''\n", Files.readString(data.resolve("language.yml")));
  }

  @Test
  public void editorLeavesActiveLocaleUnchangedAndRejectsInvalidOrStaleEdits() throws Exception {
    Path data = temporaryFolder.newFolder().toPath();
    Path languages = Files.createDirectories(data.resolve("languages"));
    Files.writeString(data.resolve("language.yml"), "prefix: ''\n");
    Files.writeString(languages.resolve("fr_FR.yml"), "locale: fr_FR\nno_permission: Permission\n");
    try (RemoteLanguageCatalog remote = RemoteLanguageCatalog.load(new RemoteLanguageCatalog.Options(
        "HiddenOre", URI.create("https://raw.githubusercontent.com/VolmitSoftware/HiddenOre/"),
        "src/main/resources/languages", ".yml", "language-source.properties", languages.resolve("cache"),
        Messages.class.getClassLoader()))) {
      Messages messages = new Messages(remote, languages);
      PluginLanguageEditor.Options editor = messages.editorOptions();
      LocalizationSnapshot original = editor.loader().load("fr_FR");
      Path file = languages.resolve("overrides/fr_FR.yml");
      assertThrows(IllegalArgumentException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
          "fr_FR", Messages.NO_PERMISSION.id(), original.value(Messages.NO_PERMISSION), new TextValue("{unexpected}"))));
      assertFalse(Files.exists(file));
      TextValue replacement = new TextValue("Permission modifiee");
      editor.writer().write(new PluginLanguageEditor.Edit("fr_FR", Messages.NO_PERMISSION.id(),
          original.value(Messages.NO_PERMISSION), replacement));
      byte[] saved = Files.readAllBytes(file);
      assertThrows(IOException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
          "fr_FR", Messages.NO_PERMISSION.id(), original.value(Messages.NO_PERMISSION), new TextValue("Stale"))));
      assertArrayEquals(saved, Files.readAllBytes(file));
      assertEquals(Messages.NO_PERMISSION.englishValue(), messages.defaultSnapshot().value(Messages.NO_PERMISSION));
      assertEquals(replacement, editor.loader().load("fr_FR").value(Messages.NO_PERMISSION));
    }
  }

  @Test
  public void englishDefaultsLiveInTheTypedJavaCatalog() {
    Messages messages = new Messages();

    assertEquals("<green>[HiddenOre]</green> ", Messages.PREFIX.english());
    assertTrue(Messages.NO_PERMISSION.english().contains("You do not have permission"));
    assertEquals("[HiddenOre] You do not have permission to use this command.", text(messages.component(Messages.NO_PERMISSION)));
  }

  @Test
  public void everyDownloadableLocaleFullyCoversTheTypedCatalog() throws Exception {
    Messages messages = new Messages();
    for (String locale : VolmitLocales.nonEnglish()) {
      YamlConfiguration language = new YamlConfiguration();
      language.load(Path.of("src/main/resources/languages", locale + ".yml").toFile());
      language.set("locale", null);
      LocalizationReloadResult result = messages.reload(language, "language.yml", locale);

      assertTrue(locale, result.applied());
      for (MessageKey key : Messages.catalog().keys()) {
        assertEquals(locale + ":" + key.id(), locale, messages.snapshot().sourceLocale(key));
      }
    }
  }

  @Test
  public void downloadableResourceSetExactlyMatchesSharedManifest() throws Exception {
    Set<String> expected = VolmitLocales.nonEnglish().stream()
        .map(locale -> locale + ".yml")
        .collect(Collectors.toUnmodifiableSet());
    try (Stream<Path> paths = Files.list(Path.of("src/main/resources/languages"))) {
      Set<String> actual = paths
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .collect(Collectors.toUnmodifiableSet());
      assertEquals(expected, actual);
    }
    assertFalse(expected.contains(VolmitLocales.ENGLISH + ".yml"));
  }

  @Test
  public void installedCatalogAndPersonalChoiceUseTheSharedRuntime() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath();
    Files.copy(Path.of("src/main/resources/languages/de_DE.yml"), directory.resolve("de_DE.yml"));
    Messages messages = new Messages();
    UUID player = UUID.randomUUID();
    try (RemoteLanguageCatalog remote = RemoteLanguageCatalog.load(new RemoteLanguageCatalog.Options(
        "HiddenOre", URI.create("https://raw.githubusercontent.com/VolmitSoftware/HiddenOre/"),
        "src/main/resources/languages", ".yml", "language-source.properties", directory.resolve("cache"),
        Messages.class.getClassLoader()));
         PluginLanguageService service = new PluginLanguageService(new PluginLanguageService.Options(
             directory.resolve("preferences.properties"), VolmitLocales::all, () -> "en_US", messages::defaultSnapshot,
             locale -> {
               Messages selected = new Messages(remote, directory);
               selected.reload(new YamlConfiguration(), "language.yml", locale);
               return selected.defaultSnapshot();
             }, (locale, prepared) -> messages.install(prepared), Logger.getAnonymousLogger()))) {
      messages.languageService(service);
      String english = text(messages.component(Messages.NO_PERMISSION));
      service.selectPlayer(player, "de_DE").get(5, TimeUnit.SECONDS);
      String translated = LanguageAudience.call(player, () -> text(messages.component(Messages.NO_PERMISSION)));
      assertFalse(english.equals(translated));
      assertEquals(english, text(messages.component(Messages.NO_PERMISSION)));
      service.clearPlayer(player).get(5, TimeUnit.SECONDS);
      assertEquals(english, LanguageAudience.call(player, () -> text(messages.component(Messages.NO_PERMISSION))));
    }
  }

  @Test
  public void generatedFilesKeepLanguageAndMetricsInTheMainConfig() throws Exception {
    YamlConfiguration config = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/hiddenore.yml").toFile());
    YamlConfiguration language = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/language.yml").toFile());
    List<String> keys = config.getKeys(false).stream().toList();

    assertEquals("language", keys.get(0));
    assertEquals("metrics", keys.get(1));
    assertFalse(language.contains("locale"));
  }

  @Test
  public void externalOverlayIsImmutableAfterAtomicReload() {
    Messages messages = new Messages();
    YamlConfiguration language = new YamlConfiguration();
    language.set("prefix", "<gold>[Erz]</gold> ");
    language.set("no_permission", "<red>Keine Berechtigung.</red>");
    language.set("director.help.navigation.page", "Seite");
    language.set("command.description.reload", "HiddenOre-Konfiguration neu laden");

    LocalizationReloadResult result = messages.reload(language, "translations/de_DE.yml", "de_DE");
    language.set("prefix", "Changed after reload");
    language.set("no_permission", "Changed after reload");

    assertTrue(result.applied());
    assertEquals("[Erz] Keine Berechtigung.", text(messages.component(Messages.NO_PERMISSION)));
    DirectorTextResolver resolver = messages.directorResolver();
    assertEquals("Seite", resolver.resolve(DirectorHelpMessages.PAGE));
    assertEquals("HiddenOre-Konfiguration neu laden", resolver.resolve(Messages.COMMAND_RELOAD_DESCRIPTION));
  }

  @Test
  public void invalidReloadRetainsTheLastGoodSnapshot() {
    Messages messages = new Messages();
    YamlConfiguration valid = new YamlConfiguration();
    valid.set("no_permission", "<red>Accès refusé.</red>");
    messages.reload(valid, "translations/fr_FR.yml", "fr_FR");

    YamlConfiguration placeholderDrift = new YamlConfiguration();
    placeholderDrift.set("no_permission", "<red>Bonjour {player}</red>");

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> messages.reload(placeholderDrift, "translations/fr_FR.yml", "fr_FR")
    );

    assertTrue(exception.getMessage().contains("localization reload rejected"));
    assertEquals("[HiddenOre] Accès refusé.", text(messages.component(Messages.NO_PERMISSION)));
  }

  @Test
  public void malformedMiniMessageAndWrongShapesAreRejected() {
    Messages messages = new Messages();
    YamlConfiguration valid = new YamlConfiguration();
    valid.set("reloaded", "<gold>Dernière bonne version.</gold>");
    messages.reload(valid, "language.yml", "en_US");
    YamlConfiguration malformed = new YamlConfiguration();
    malformed.set("reloaded", "<red>Missing close");
    IllegalArgumentException malformedException = assertThrows(
        IllegalArgumentException.class,
        () -> messages.reload(malformed, "language.yml", "en_US")
    );

    YamlConfiguration wrongShape = new YamlConfiguration();
    wrongShape.set("reloaded", List.of("Not", "a", "string"));
    IllegalArgumentException shapeException = assertThrows(
        IllegalArgumentException.class,
        () -> messages.reload(wrongShape, "language.yml", "en_US")
    );

    assertTrue(malformedException.getMessage().contains("invalid MiniMessage"));
    assertEquals("[HiddenOre] Dernière bonne version.", text(messages.component(Messages.RELOADED)));
    assertTrue(shapeException.getMessage().contains("expected a non-empty message string"));
    assertEquals("[HiddenOre] Dernière bonne version.", text(messages.component(Messages.RELOADED)));
  }

  @Test
  public void unknownOverlayKeysAreRejectedWithoutChangingDefaults() {
    Messages messages = new Messages();
    YamlConfiguration valid = new YamlConfiguration();
    valid.set("debug_enabled", "<green>Diagnose aktiv.</green>");
    messages.reload(valid, "language.yml", "en_US");
    YamlConfiguration language = new YamlConfiguration();
    language.set("unknown_message", "Unexpected");

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> messages.reload(language, "language.yml", "en_US")
    );

    assertTrue(exception.getMessage().contains("UNUSED_KEY"));
    assertEquals("[HiddenOre] Diagnose aktiv.", text(messages.component(Messages.DEBUG_ENABLED)));
  }

  @Test
  public void untrustedNamedArgumentsCannotInjectMiniMessage() {
    Messages messages = new Messages();
    String maliciousMaterial = "{amount}<click:run_command:'/op @s'>diamond</click>";
    Component component = messages.component(
        Messages.DEBUG_RANDOM_DROP,
        MessageArgs.builder()
            .untrusted("material", maliciousMaterial)
            .untrusted("amount", 4)
            .build()
    );

    assertEquals("[HiddenOre] Random drop: " + maliciousMaterial + " x4", text(component));
    assertFalse(hasClickEvent(component));
  }

  @Test
  public void sharedComponentBridgePreservesColorClickAndHoverEvents() {
    Messages messages = new Messages();
    YamlConfiguration language = new YamlConfiguration();
    language.set("reloaded",
        "<click:run_command:'/hiddenore reload'><hover:show_text:'Reload HiddenOre'>"
            + "<#12ab34>Reload now</#12ab34></hover></click>");
    messages.reload(language, "language.yml", "en_US");

    ComponentText bridged = ComponentText.component(messages.component(Messages.RELOADED));
    Component restored = MiniMessage.miniMessage().deserialize(bridged.miniMessage());

    assertEquals("[HiddenOre] Reload now", bridged.plain());
    assertTrue(hasClickEvent(restored));
    assertTrue(hasHoverEvent(restored));
    assertTrue(hasColor(restored, TextColor.color(0x12ab34)));
  }

  @Test
  public void overlaysCannotPlaceUntrustedArgumentsInsideMiniMessageTags() {
    Messages messages = new Messages();
    YamlConfiguration language = new YamlConfiguration();
    language.set(
        "debug.random_drop",
        "<click:run_command:'/{material}'>Random drop: {material} x{amount}</click>"
    );

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> messages.reload(language, "language.yml", "en_US")
    );

    assertTrue(exception.getMessage().contains("placeholders cannot be used inside MiniMessage tags"));
    assertFalse(hasClickEvent(messages.component(
        Messages.DEBUG_RANDOM_DROP,
        MessageArgs.builder()
            .untrusted("material", "diamond")
            .untrusted("amount", 1)
            .build()
    )));
  }

  @Test
  public void namedArgumentsMustMatchTheWholeTemplate() {
    Messages messages = new Messages();

    assertThrows(
        IllegalArgumentException.class,
        () -> messages.component(
            Messages.DEBUG_RANDOM_DROP,
            MessageArgs.builder().untrusted("material", "diamond").build()
        )
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> messages.component(
            Messages.DEBUG_RANDOM_DROP,
            MessageArgs.builder()
                .untrusted("material", "diamond")
                .untrusted("amount", 1)
                .untrusted("extra", "value")
                .build()
        )
    );
  }

  @Test
  public void operationalLanguageSettingsAreNotTreatedAsMessageKeys() {
    Messages messages = new Messages();
    YamlConfiguration language = new YamlConfiguration();
    language.set("config_reloaded_sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
    language.set("config_reloaded_sound_volume", 1.0);
    language.set("config_reloaded_sound_pitch", 1.6);

    assertTrue(messages.reload(language, "language.yml", "en_US").applied());
  }

  @Test
  public void directorHelpUsesTheSameOverlayAndProducesValidMiniMessage() {
    Messages messages = new Messages();
    YamlConfiguration language = new YamlConfiguration();
    language.set("command.description.reload", "HiddenOre neu laden");
    language.set("director.help.no_parameters", "Keine Parameter.");
    messages.reload(language, "language.yml", "de_DE");
    DirectorRuntimeEngine engine = DirectorEngineFactory.create(new HelpCommands());
    DirectorMiniMenu.DirectorHelpPage page = DirectorMiniMenu.resolveHelp(engine, List.of()).orElseThrow();
    List<String> rendered = DirectorMiniMenu.render(
        page,
        DirectorMiniMenu.Theme.reactBlue(),
        messages.directorResolver()
    );

    assertTrue(String.join("\n", rendered).contains("HiddenOre neu laden"));
    assertTrue(String.join("\n", rendered).contains("Keine Parameter."));
    for (String line : rendered) {
      MiniMessage.miniMessage().deserialize(line);
    }
  }

  private static List<String> texts(List<Component> components) {
    return components.stream().map(MessagesTest::text).toList();
  }

  private static String text(Component component) {
    StringBuilder text = new StringBuilder();
    appendText(component, text);
    return text.toString();
  }

  private static void appendText(Component component, StringBuilder text) {
    if (component instanceof TextComponent textComponent) {
      text.append(textComponent.content());
    }
    for (Component child : component.children()) {
      appendText(child, text);
    }
  }

  private static boolean hasClickEvent(Component component) {
    if (component.clickEvent() != null) {
      return true;
    }
    for (Component child : component.children()) {
      if (hasClickEvent(child)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasHoverEvent(Component component) {
    if (component.hoverEvent() != null) {
      return true;
    }
    for (Component child : component.children()) {
      if (hasHoverEvent(child)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasColor(Component component, TextColor color) {
    if (color.equals(component.color())) {
      return true;
    }
    for (Component child : component.children()) {
      if (hasColor(child, color)) {
        return true;
      }
    }
    return false;
  }

  @Director(name = "hiddenore", description = "HiddenOre command root", descriptionKey = "command.description.root")
  public static final class HelpCommands {
    @Director(name = "reload", description = "Reload HiddenOre configuration and language files", descriptionKey = "command.description.reload")
    public void reload() {
    }
  }
}
