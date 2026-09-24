#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$ROOT/target"
LOG="$ROOT/build.log"
EXIT_CODE=0

main() {
    local version
    local neoforge_1211_version

    version="$(sed -n 's/^mod_version=//p' "$ROOT/gradle.properties" | head -n 1)"
    neoforge_1211_version="$(sed -n 's/^neoforge_1211_mod_version=//p' "$ROOT/gradle.properties" | head -n 1)"

    if [[ -z "$version" ]]; then
        echo "Could not read mod_version from gradle.properties."
        return 1
    fi
    if [[ -z "$neoforge_1211_version" ]]; then
        echo "Could not read neoforge_1211_mod_version from gradle.properties."
        return 1
    fi

    mkdir -p "$TARGET" || {
        echo "Could not create target directory: $TARGET"
        return 1
    }

    find "$TARGET" -type f -name '*.jar' -delete || {
        echo "Could not remove existing jar files from target: $TARGET"
        return 1
    }

    build_neoforge_variant         "1.21.1" "21" "13.0.11" "21.1.223" "4.0.42"         "https://piston-data.mojang.com/v1/objects/30c73b1c5da787909b2f73340419fdf13b9def88/client.jar"         "$neoforge_1211_version" || return 1

    build_variant         "1.21.11" "21" "1.21.11-R0.1-SNAPSHOT" "1.21" "19.0.1"         "0.141.4+1.21.11" "21.11.42" "4.0.42" "true" "false" ""         "$version" || return 1

    build_variant         "26.1" "25" "26.1-R0.1-SNAPSHOT" "26.1" "20.0.4"         "0.145.1+1.21.1" "26.1.0.19-beta" "4.0.42" "false" "true"         "https://piston-data.mojang.com/v1/objects/191771837687b766537a8c4607cb6fad79c533a1/client.jar"         "$version" || return 1

    echo "Built all ZstdNet variants into: $TARGET"
}

select_java() {
    local java_version="$1"
    local selected_java_home=""

    case "$java_version" in
        21) selected_java_home="${JAVA_HOME_21_X64:-}" ;;
        25) selected_java_home="${JAVA_HOME_25_X64:-}" ;;
        *)
            echo "Unsupported Java version: $java_version"
            return 1
            ;;
    esac

    if [[ -z "$selected_java_home" ]]; then
        return 0
    fi

    if [[ "$selected_java_home" =~ ^[A-Za-z]:\\ ]]; then
        selected_java_home="$(cygpath -u "$selected_java_home")"
    fi

    if [[ ! -x "$selected_java_home/bin/java" && ! -f "$selected_java_home/bin/java.exe" ]]; then
        echo "Java $java_version was selected but JAVA_HOME does not contain java: $selected_java_home"
        return 1
    fi

    export JAVA_HOME="$selected_java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Using Java $java_version: $JAVA_HOME"
}

build_neoforge_variant() {
    local mc_version="$1"
    local java_version="$2"
    local architectury_api_version="$3"
    local neoforge_version="$4"
    local neoforge_fml_loader_version="$5"
    local minecraft_client_url="$6"
    local neoforge_1211_version="$7"

    select_java "$java_version" || return 1

    echo
    echo "Building ZstdNet NeoForge server-client mod for Minecraft $mc_version..."

    local -a gradle_args=(
        --no-daemon
        :neoforge:clean
        :neoforge:build
        "-Pminecraft_version=$mc_version"
        "-Pjava_version=$java_version"
        "-Parchitectury_api_version=$architectury_api_version"
        "-Pneoforge_version=$neoforge_version"
        "-Pneoforge_fml_loader_version=$neoforge_fml_loader_version"
        -Pneoforge_variant=server-1211
        -Pclient_loom_enabled=true
        -Pfabric_loom_enabled=false
        -Pnamed_client_jar_enabled=false
    )

    "$ROOT/gradlew" "${gradle_args[@]}" || {
        echo "NeoForge server-client build failed for Minecraft $mc_version."
        return 1
    }

    local neoforge_jar="$ROOT/neoforge/build/libs/neoforge-$neoforge_1211_version.jar"
    local output_jar="$TARGET/ZstdNet-$mc_version-neoforge-server-client-$neoforge_1211_version.jar"

    if [[ ! -f "$neoforge_jar" ]]; then
        echo "Expected NeoForge jar was not found: $neoforge_jar"
        return 1
    fi

    cp -f "$neoforge_jar" "$output_jar" || {
        echo "Could not copy NeoForge server-client jar to target."
        return 1
    }

    echo "Built NeoForge server-client mod: $output_jar"
}

build_variant() {
    local mc_version="$1"
    local java_version="$2"
    local spigot_api_version="$3"
    local plugin_api_version="$4"
    local architectury_api_version="$5"
    local fabric_api_version="$6"
    local neoforge_version="$7"
    local neoforge_fml_loader_version="$8"
    local client_loom_enabled="$9"
    local named_client_jar_enabled="${10}"
    local minecraft_client_url="${11}"
    local version="${12}"

    select_java "$java_version" || return 1

    echo
    echo "Building ZstdNet for Minecraft $mc_version..."

    local -a gradle_args=(
        --no-daemon
        clean
        build
        "-Pminecraft_version=$mc_version"
        "-Pjava_version=$java_version"
        "-Pspigot_api_version=$spigot_api_version"
        "-Pplugin_api_version=$plugin_api_version"
        "-Parchitectury_api_version=$architectury_api_version"
        "-Pfabric_api_version=$fabric_api_version"
        "-Pneoforge_version=$neoforge_version"
        "-Pneoforge_fml_loader_version=$neoforge_fml_loader_version"
        "-Pclient_loom_enabled=$client_loom_enabled"
        -Pneoforge_variant=client
        "-Pnamed_client_jar_enabled=$named_client_jar_enabled"
        "-Pminecraft_client_url=$minecraft_client_url"
    )

    "$ROOT/gradlew" "${gradle_args[@]}" || {
        echo "Build failed for Minecraft $mc_version."
        return 1
    }

    local plugin_jar="$ROOT/spigot/build/libs/spigot-$version.jar"
    local fabric_jar="$ROOT/fabric/build/libs/fabric-$version.jar"
    local neoforge_jar="$ROOT/neoforge/build/libs/neoforge-$version.jar"
    local output_plugin="$TARGET/ZstdNet-$mc_version-spigot-$version.jar"
    local output_fabric="$TARGET/ZstdNet-$mc_version-fabric-$version.jar"
    local output_neoforge="$TARGET/ZstdNet-$mc_version-neoforge-$version.jar"

    for jar in "$plugin_jar" "$fabric_jar" "$neoforge_jar"; do
        if [[ ! -f "$jar" ]]; then
            echo "Expected build jar was not found: $jar"
            return 1
        fi
    done

    cp -f "$plugin_jar" "$output_plugin" || {
        echo "Could not copy plugin jar to target."
        return 1
    }
    cp -f "$fabric_jar" "$output_fabric" || {
        echo "Could not copy Fabric client jar to target."
        return 1
    }
    cp -f "$neoforge_jar" "$output_neoforge" || {
        echo "Could not copy NeoForge client jar to target."
        return 1
    }

    echo "Built plugin: $output_plugin"
    echo "Built Fabric client: $output_fabric"
    echo "Built NeoForge client: $output_neoforge"
}

main 2>&1 | tee "$LOG"
EXIT_CODE=${PIPESTATUS[0]}
exit "$EXIT_CODE"
