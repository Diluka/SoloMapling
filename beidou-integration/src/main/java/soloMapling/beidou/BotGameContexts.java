package soloMapling.beidou;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;

/** Exact registered-bot identity over the shared read-only native structure checks. */
public final class BotGameContexts {
    private BotGameContexts() { }

    public static Binding capture(Server server, Character character, BotClient client, World world, Channel channel) {
        requireBot(character, client);
        GameContext.CharacterRef ref = NativeContextChecks.captureCharacter(server, character, client, world, channel);
        requireBot(character, client);
        return new Binding(ref);
    }

    /** Revalidates the captured epoch and native objects; it never refreshes an old binding. */
    public static void requireCurrent(Server server, Binding binding) {
        GameContext.CharacterRef ref = requireBinding(binding);
        requireBot(ref.character(), ref.client());
        NativeContextChecks.requireCharacterCurrent(server, ref);
        requireBot(ref.character(), ref.client());
    }

    public static GameContext.SceneRef captureScene(Server server, Binding binding) {
        GameContext.CharacterRef ref = requireBinding(binding);
        requireBot(ref.character(), ref.client());
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, ref);
        requireBot(ref.character(), ref.client());
        return scene;
    }

    public static void requireSceneCurrent(Server server, Binding binding, GameContext.SceneRef scene) {
        GameContext.CharacterRef ref = requireBinding(binding);
        requireBot(ref.character(), ref.client());
        if (scene == null || scene.actor() != ref) throw new GameContext.Invalid(GameContext.Failure.SCENE_CHANGED);
        NativeContextChecks.requireSceneCurrent(server, scene);
        requireBot(ref.character(), ref.client());
    }

    private static GameContext.CharacterRef requireBinding(Binding binding) {
        if (binding == null) throw new GameContext.Invalid(GameContext.Failure.CHARACTER_CHANGED);
        return binding.ref;
    }

    private static void requireBot(Character character, org.gms.client.Client client) {
        try {
            if (character == null || !(client instanceof BotClient) || character.getClient() != client
                    || !BotRegistry.isRegistered(character)) {
                throw new GameContext.Invalid(GameContext.Failure.CHARACTER_CHANGED);
            }
        } catch (GameContext.Invalid invalid) {
            throw invalid;
        } catch (RuntimeException ignored) {
            throw new GameContext.Invalid(GameContext.Failure.READ_UNAVAILABLE);
        }
    }

    /** A reference captured only after registry authorization, independent of MCP settings or BotSM. */
    public static final class Binding {
        private final GameContext.CharacterRef ref;

        private Binding(GameContext.CharacterRef ref) { this.ref = ref; }
        public GameContext.CharacterRef characterRef() { return ref; }
    }
}
