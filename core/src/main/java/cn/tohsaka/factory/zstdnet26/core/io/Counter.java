package cn.tohsaka.factory.zstdnet26.core.io;

@FunctionalInterface
public interface Counter {
    void add(long bytes);
}
