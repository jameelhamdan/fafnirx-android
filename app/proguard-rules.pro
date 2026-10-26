# gRPC finds its name resolver, load balancer and transport by class name.
-keep class io.grpc.internal.DnsNameResolverProvider { *; }
-keep class io.grpc.internal.PickFirstLoadBalancerProvider { *; }
-keep class io.grpc.okhttp.OkHttpChannelProvider { *; }
-keep class io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider { *; }

# protobuf-lite reads the fields of generated messages by name.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
