package dev.logos.port;

import java.util.function.Function;

import net.minecraft.network.codec.StreamCodec;

/** Stands in for the 7- and 8-field {@code StreamCodec.composite} of 26.1; 1.21.1's stops at six. */
public final class StreamCodecs {
    private StreamCodecs() {}

    @FunctionalInterface
    public interface Function7<A, B, C, D, E, F, G, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g);
    }

    @FunctionalInterface
    public interface Function8<A, B, C, D, E, F, G, H, R> {
        R apply(A a, B b, C c, D d, E e, F f, G g, H h);
    }

    public static <B, C, T1, T2, T3, T4, T5, T6, T7> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1, StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3, StreamCodec<? super B, T4> c4, Function<C, T4> g4,
            StreamCodec<? super B, T5> c5, Function<C, T5> g5, StreamCodec<? super B, T6> c6, Function<C, T6> g6,
            StreamCodec<? super B, T7> c7, Function<C, T7> g7, Function7<T1, T2, T3, T4, T5, T6, T7, C> make) {
        return new StreamCodec<>() {
            @Override
            public C decode(B buf) {
                return make.apply(c1.decode(buf), c2.decode(buf), c3.decode(buf), c4.decode(buf), c5.decode(buf), c6.decode(buf), c7.decode(buf));
            }

            @Override
            public void encode(B buf, C value) {
                c1.encode(buf, g1.apply(value));
                c2.encode(buf, g2.apply(value));
                c3.encode(buf, g3.apply(value));
                c4.encode(buf, g4.apply(value));
                c5.encode(buf, g5.apply(value));
                c6.encode(buf, g6.apply(value));
                c7.encode(buf, g7.apply(value));
            }
        };
    }

    public static <B, C, T1, T2, T3, T4, T5, T6, T7, T8> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1, StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3, StreamCodec<? super B, T4> c4, Function<C, T4> g4,
            StreamCodec<? super B, T5> c5, Function<C, T5> g5, StreamCodec<? super B, T6> c6, Function<C, T6> g6,
            StreamCodec<? super B, T7> c7, Function<C, T7> g7, StreamCodec<? super B, T8> c8, Function<C, T8> g8,
            Function8<T1, T2, T3, T4, T5, T6, T7, T8, C> make) {
        return new StreamCodec<>() {
            @Override
            public C decode(B buf) {
                return make.apply(c1.decode(buf), c2.decode(buf), c3.decode(buf), c4.decode(buf), c5.decode(buf), c6.decode(buf), c7.decode(buf),
                        c8.decode(buf));
            }

            @Override
            public void encode(B buf, C value) {
                c1.encode(buf, g1.apply(value));
                c2.encode(buf, g2.apply(value));
                c3.encode(buf, g3.apply(value));
                c4.encode(buf, g4.apply(value));
                c5.encode(buf, g5.apply(value));
                c6.encode(buf, g6.apply(value));
                c7.encode(buf, g7.apply(value));
                c8.encode(buf, g8.apply(value));
            }
        };
    }
}
