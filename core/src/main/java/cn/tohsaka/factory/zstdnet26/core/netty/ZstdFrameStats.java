package cn.tohsaka.factory.zstdnet26.core.netty;

public interface ZstdFrameStats {
    ZstdFrameStats NONE = new ZstdFrameStats() {
        @Override
        public void inbound(long rawBytes, long wireBytes) {
        }

        @Override
        public void outbound(long rawBytes, long wireBytes) {
        }
    };

    void inbound(long rawBytes, long wireBytes);

    void outbound(long rawBytes, long wireBytes);
}
