package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.alchemy.ProcessCard;
import dev.logos.block.AlchemyTableBlockEntity;
import dev.logos.block.AlchemyTableUI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Client → server, from the alchemy table's canvas: lay, move or take off a process card, designate
 * or clear its inputs, or route one of its products. {@code card} is the card's place on the table
 * (for {@link Action#ADD}, the process); {@code x} and {@code y} are a position, an input slot or a
 * route target; {@code item} is an item id, empty for none.
 */
public record AlchemyCardPayload(BlockPos table, Action action, int card, int x, int y, String item, ItemResource what) implements CustomPacketPayload {
    public enum Action { ADD, MOVE, REMOVE, DESIGNATE, CLEAR, ROUTE }

    public static final Type<AlchemyCardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "alchemy_card"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AlchemyCardPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AlchemyCardPayload::table, ByteBufCodecs.idMapper(i -> Action.values()[i], Action::ordinal), AlchemyCardPayload::action,
            ByteBufCodecs.VAR_INT, AlchemyCardPayload::card, ByteBufCodecs.VAR_INT, AlchemyCardPayload::x, ByteBufCodecs.VAR_INT, AlchemyCardPayload::y,
            ByteBufCodecs.STRING_UTF8, AlchemyCardPayload::item, ItemStack.OPTIONAL_STREAM_CODEC.map(ItemResource::of, what -> what.toStack(1)), AlchemyCardPayload::what, AlchemyCardPayload::new);

    public static AlchemyCardPayload add(BlockPos table, Process process, int x, int y) {
        return new AlchemyCardPayload(table, Action.ADD, process.ordinal(), x, y, "", ItemResource.EMPTY);
    }

    public static AlchemyCardPayload move(BlockPos table, int card, int x, int y) {
        return new AlchemyCardPayload(table, Action.MOVE, card, x, y, "", ItemResource.EMPTY);
    }

    public static AlchemyCardPayload remove(BlockPos table, int card) {
        return new AlchemyCardPayload(table, Action.REMOVE, card, 0, 0, "", ItemResource.EMPTY);
    }

    /** Designates exactly {@code what} for the card's first free input slot. */
    public static AlchemyCardPayload designate(BlockPos table, int card, ItemResource what) {
        return new AlchemyCardPayload(table, Action.DESIGNATE, card, -1, 0, "", what);
    }

    /** Clears the designations of the card's empty input slots. */
    public static AlchemyCardPayload clear(BlockPos table, int card) {
        return new AlchemyCardPayload(table, Action.CLEAR, card, 0, 0, "", ItemResource.EMPTY);
    }

    /** Wires card {@code card}'s product {@code product} (exactly that kind) to {@code target}. */
    public static AlchemyCardPayload route(BlockPos table, int card, ItemResource product, int target) {
        return new AlchemyCardPayload(table, Action.ROUTE, card, target, 0, "", product);
    }

    @Override
    public Type<AlchemyCardPayload> type() {
        return TYPE;
    }

    public static void handle(AlchemyCardPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || !sp.isWithinBlockInteractionRange(p.table, 1.0)) return;
        if (!(sp.level().getBlockEntity(p.table) instanceof AlchemyTableBlockEntity table)) return;
        if (p.action == Action.ADD) {
            if (p.card >= 0 && p.card < Process.values().length) {
                table.addCard(Process.values()[p.card], AlchemyTableUI.clampX(p.x), AlchemyTableUI.clampY(p.y));
            }
            return;
        }
        if (p.card < 0 || p.card >= table.cards().size()) return;
        ProcessCard card = table.cards().get(p.card);
        Item item = p.item.isEmpty() ? null : BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(p.item)).orElse(null);
        switch (p.action) {
            case MOVE -> card.moveTo(AlchemyTableUI.clampX(p.x), AlchemyTableUI.clampY(p.y));
            case REMOVE -> table.removeCard(p.card);
            case DESIGNATE -> {
                if (p.what.isEmpty()) return;
                for (int i = 0; i < ProcessCard.SLOTS; i++) if (card.designated(i).equals(p.what)) return;
                for (int i = 0; i < ProcessCard.SLOTS; i++) if (card.designated(i).isEmpty() && card.designate(i, p.what)) return;
            }
            case CLEAR -> {
                for (int i = 0; i < ProcessCard.SLOTS; i++) if (card.inputs().getResource(i).isEmpty()) card.designate(i, ItemResource.EMPTY);
            }
            case ROUTE -> {
                boolean valid = p.x == ProcessCard.KEEP || p.x == ProcessCard.PRODUCTS || p.x >= 0 && p.x < table.cards().size() && p.x != p.card;
                if (!p.what.isEmpty() && valid) card.setRoute(p.what, p.x);
            }
            default -> {}
        }
    }
}
