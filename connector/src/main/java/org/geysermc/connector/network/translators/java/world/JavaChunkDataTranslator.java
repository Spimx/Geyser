/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.translators.java.world;

import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerChunkDataPacket;
import com.nukkitx.nbt.NBTOutputStream;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtUtils;
import com.nukkitx.network.VarInts;
import com.nukkitx.protocol.bedrock.packet.LevelChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.BiomeTranslator;
import org.geysermc.connector.network.translators.PacketTranslator;
import org.geysermc.connector.network.translators.Translator;
import org.geysermc.connector.network.translators.world.chunk.ChunkSection;
import org.geysermc.connector.utils.ChunkUtils;

@Translator(packet = ServerChunkDataPacket.class)
public class JavaChunkDataTranslator extends PacketTranslator<ServerChunkDataPacket> {

    /**
     * Determines if we should process non-full chunks
     */
    private final boolean isCacheChunks;

    public JavaChunkDataTranslator() {
        isCacheChunks = GeyserConnector.getInstance().getConfig().isCacheChunks();
    }

    @Override
    public void translate(ServerChunkDataPacket packet, GeyserSession session) {
        if (session.isSpawned()) {
            ChunkUtils.updateChunkPosition(session, session.getPlayerEntity().getPosition().toInt());
        }

        if (packet.getColumn().getBiomeData() == null && !isCacheChunks) {
            // Non-full chunk without chunk caching
            session.getConnector().getLogger().debug("Not sending non-full chunk because chunk caching is off.");
            return;
        }

        GeyserConnector.getInstance().getGeneralThreadPool().execute(() -> {
            try {
                // Non-full chunks don't have all the chunk data, and Bedrock won't accept that
                final boolean isNonFullChunk = (packet.getColumn().getBiomeData() == null);

                ChunkUtils.ChunkData chunkData = ChunkUtils.translateToBedrock(session, packet.getColumn(), isNonFullChunk);
                ByteBuf byteBuf = Unpooled.buffer(32);
                ChunkSection[] sections = chunkData.sections;

                int sectionCount = sections.length - 1;
                while (sectionCount >= 0 && sections[sectionCount].isEmpty()) {
                    sectionCount--;
                }
                sectionCount++;

                for (int i = 0; i < sectionCount; i++) {
                    ChunkSection section = chunkData.sections[i];
                    section.writeToNetwork(byteBuf);
                }

                byte[] bedrockBiome;
                if (packet.getColumn().getBiomeData() == null) {
                    bedrockBiome = BiomeTranslator.toBedrockBiome(session.getConnector().getWorldManager().getBiomeDataAt(session, packet.getColumn().getX(), packet.getColumn().getZ()));
                } else {
                    bedrockBiome = BiomeTranslator.toBedrockBiome(packet.getColumn().getBiomeData());
                }

                byteBuf.writeBytes(bedrockBiome); // Biomes - 256 bytes
                byteBuf.writeByte(0); // Border blocks - Edu edition only
                VarInts.writeUnsignedInt(byteBuf, 0); // extra data length, 0 for now

                ByteBufOutputStream stream = new ByteBufOutputStream(Unpooled.buffer());
                NBTOutputStream nbtStream = NbtUtils.createNetworkWriter(stream);
                for (NbtMap blockEntity : chunkData.getBlockEntities()) {
                    nbtStream.writeTag(blockEntity);
                }

                byteBuf.writeBytes(stream.buffer());

                byte[] payload = new byte[byteBuf.writerIndex()];
                byteBuf.readBytes(payload);

                LevelChunkPacket levelChunkPacket = new LevelChunkPacket();
                levelChunkPacket.setSubChunksLength(sectionCount);
                levelChunkPacket.setCachingEnabled(false);
                levelChunkPacket.setChunkX(packet.getColumn().getX());
                levelChunkPacket.setChunkZ(packet.getColumn().getZ());
                levelChunkPacket.setData(payload);
                session.sendUpstreamPacket(levelChunkPacket);

                session.getChunkCache().addToCache(packet.getColumn());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
