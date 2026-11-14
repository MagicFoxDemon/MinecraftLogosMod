package dev.logos.item;

import java.util.Set;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * Loot condition {@code logos:in_end_ship}: whether the container being looted stands in an end city's ship. The
 * ship's chests roll the same table as the city's ({@code chests/end_city_treasure}), so the 虚空之书 tells them
 * apart by the structure piece around the chest, whose template is {@code ship}.
 */
public record InEndShip() implements LootItemCondition {
    public static final MapCodec<InEndShip> CODEC = MapCodec.unit(new InEndShip());

    @Override
    public MapCodec<InEndShip> codec() {
        return CODEC;
    }

    @Override
    public Set<ContextKey<?>> getReferencedContextParams() {
        return Set.of(LootContextParams.ORIGIN);
    }

    @Override
    public boolean test(LootContext context) {
        Vec3 origin = context.getOptionalParameter(LootContextParams.ORIGIN);
        if (origin == null) return false;
        ServerLevel level = context.getLevel();
        BlockPos pos = BlockPos.containing(origin);
        StructureStart city = level.structureManager().getStructureWithPieceAt(pos, s -> s.is(BuiltinStructures.END_CITY));
        if (!city.isValid()) return false;
        for (StructurePiece piece : city.getPieces()) {
            if (piece.getType() != StructurePieceType.END_CITY_PIECE || !piece.getBoundingBox().isInside(pos)) continue;
            // The template's name is only reachable through the piece's saved form.
            if ("ship".equals(piece.createTag(StructurePieceSerializationContext.fromLevel(level)).getStringOr("Template", ""))) return true;
        }
        return false;
    }
}
