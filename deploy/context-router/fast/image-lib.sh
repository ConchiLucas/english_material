#!/usr/bin/env sh

EM_BASE_REPOSITORY=${EM_BASE_REPOSITORY:-english-material/backend-base}
EM_BASE_POINTER_TAG=${EM_BASE_POINTER_TAG:-current}
EM_BASE_SCHEMA_VERSION=1

EM_LABEL_ROLE=local.english-material.backend.base.role
EM_LABEL_KEY=local.english-material.backend.base.key
EM_LABEL_SCHEMA=local.english-material.backend.base.schema
EM_LABEL_DEPENDENCIES=local.english-material.backend.base.dependencies-sha256
EM_LABEL_LOADER=local.english-material.backend.base.loader-sha256
EM_LABEL_DOCKERFILE=local.english-material.backend.base.dockerfile-sha256
EM_LABEL_JAVA_IMAGE=local.english-material.backend.base.java-image-id
EM_LABEL_NODE_IMAGE=local.english-material.backend.base.node-image-id
EM_LABEL_APP_UID=local.english-material.backend.base.app-uid
EM_LABEL_CODEX_VERSION=local.english-material.backend.base.codex-version
EM_APPLICATION_BASE_LABEL=local.english-material.backend.dependency-base-key

em_sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

em_file_hash() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

em_prepare_layers() {
  em_jar_file=$1
  em_layers_dir=$2

  if [ ! -s "$em_jar_file" ]; then
    echo "[ERROR] 未找到英语材料后端可执行 JAR：$em_jar_file" >&2
    return 1
  fi

  rm -rf -- "$em_layers_dir"
  mkdir -p "$em_layers_dir"
  java -Djarmode=tools -jar "$em_jar_file"     extract --layers --launcher --destination "$em_layers_dir"

  for em_required_layer in dependencies spring-boot-loader snapshot-dependencies application; do
    if [ ! -d "$em_layers_dir/$em_required_layer" ]; then
      echo "[ERROR] Spring Boot JAR 缺少分层目录：$em_required_layer" >&2
      return 1
    fi
  done
}

em_layer_hash() {
  em_layer_dir=$1
  if [ ! -d "$em_layer_dir" ]; then
    echo "[ERROR] 无法计算不存在的分层目录：$em_layer_dir" >&2
    return 1
  fi

  (
    cd "$em_layer_dir"
    find . -type f -print       | LC_ALL=C sort       | while IFS= read -r em_relative_file; do
          printf '%s\n' "$em_relative_file"
          em_file_hash "$em_relative_file"
        done       | em_sha256_stream
  )
}

em_base_key() {
  em_dependencies_hash=$1
  em_loader_hash=$2
  em_dockerfile_hash=$3
  em_java_image_id=$4
  em_node_image_id=$5
  em_app_uid=$6
  em_codex_version=$7

  printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n'     "$EM_BASE_SCHEMA_VERSION"     "$em_dependencies_hash"     "$em_loader_hash"     "$em_dockerfile_hash"     "$em_java_image_id"     "$em_node_image_id"     "$em_app_uid"     "$em_codex_version"     | em_sha256_stream     | cut -c 1-24
}

em_image_label() {
  em_image=$1
  em_label=$2
  docker image inspect --format "{{ index .Config.Labels \"$em_label\" }}" "$em_image" 2>/dev/null
}

em_image_id() {
  docker image inspect --format '{{.Id}}' "$1" 2>/dev/null || true
}

em_resolve_dependency_base() {
  em_expected_dependencies=$1
  em_expected_loader=$2
  em_pointer_image="$EM_BASE_REPOSITORY:$EM_BASE_POINTER_TAG"

  if ! docker image inspect "$em_pointer_image" >/dev/null 2>&1; then
    echo "[ERROR] 缺少英语材料后端 Full 依赖基线：$em_pointer_image" >&2
    echo "[ERROR] 请先成功执行后端 Full，再执行 Fast" >&2
    return 1
  fi

  em_actual_role=$(em_image_label "$em_pointer_image" "$EM_LABEL_ROLE")
  em_actual_schema=$(em_image_label "$em_pointer_image" "$EM_LABEL_SCHEMA")
  em_actual_dependencies=$(em_image_label "$em_pointer_image" "$EM_LABEL_DEPENDENCIES")
  em_actual_loader=$(em_image_label "$em_pointer_image" "$EM_LABEL_LOADER")
  em_actual_key=$(em_image_label "$em_pointer_image" "$EM_LABEL_KEY")
  em_actual_dockerfile=$(em_image_label "$em_pointer_image" "$EM_LABEL_DOCKERFILE")
  em_actual_java=$(em_image_label "$em_pointer_image" "$EM_LABEL_JAVA_IMAGE")
  em_actual_node=$(em_image_label "$em_pointer_image" "$EM_LABEL_NODE_IMAGE")
  em_actual_uid=$(em_image_label "$em_pointer_image" "$EM_LABEL_APP_UID")
  em_actual_codex=$(em_image_label "$em_pointer_image" "$EM_LABEL_CODEX_VERSION")

  if [ "$em_actual_role" != dependency-base ] ||      [ "$em_actual_schema" != "$EM_BASE_SCHEMA_VERSION" ] ||      [ "$em_actual_dependencies" != "$em_expected_dependencies" ] ||      [ "$em_actual_loader" != "$em_expected_loader" ] ||      [ -z "$em_actual_key" ] || [ -z "$em_actual_dockerfile" ] ||      [ -z "$em_actual_java" ] || [ -z "$em_actual_node" ] ||      [ -z "$em_actual_uid" ] || [ -z "$em_actual_codex" ]; then
    echo "[ERROR] 当前 JAR 或运行基线与最近一次 Full 不一致" >&2
    echo "[ERROR] Fast 不会复用不兼容基线，请重新执行后端 Full" >&2
    return 1
  fi

  em_immutable_image="$EM_BASE_REPOSITORY:$em_actual_key"
  if ! docker image inspect "$em_immutable_image" >/dev/null 2>&1; then
    echo "[ERROR] 基线指针缺少不可变镜像：$em_immutable_image" >&2
    return 1
  fi

  em_pointer_id=$(em_image_id "$em_pointer_image")
  em_immutable_id=$(em_image_id "$em_immutable_image")
  if [ "$em_pointer_id" != "$em_immutable_id" ]; then
    echo "[ERROR] 基线 current 指针与不可变标签不一致，请重新执行 Full" >&2
    return 1
  fi

  printf '%s\n' "$em_immutable_image"
}

em_build_application_image() {
  em_base_image=$1
  em_layers_dir=$2
  em_target_image=$3
  em_base_key_value=$4
  em_snapshot_dir="$em_layers_dir/snapshot-dependencies"
  em_application_dir="$em_layers_dir/application"
  em_build_container="english-material-backend-image-$$-$(date +%s)"

  if ! docker image inspect "$em_base_image" >/dev/null 2>&1; then
    echo "[ERROR] 依赖基线镜像不存在：$em_base_image" >&2
    return 1
  fi
  if [ ! -d "$em_snapshot_dir" ] || [ ! -d "$em_application_dir" ]; then
    echo "[ERROR] SNAPSHOT 或 application 分层不完整" >&2
    return 1
  fi

  chmod -R u=rwX,go=rX "$em_snapshot_dir" "$em_application_dir"
  trap 'docker rm -f "$em_build_container" >/dev/null 2>&1 || true' EXIT HUP INT TERM
  docker create --name "$em_build_container" "$em_base_image" >/dev/null
  docker cp "$em_snapshot_dir/." "$em_build_container:/app/"
  docker cp "$em_application_dir/." "$em_build_container:/app/"
  docker commit     --change "LABEL $EM_APPLICATION_BASE_LABEL=$em_base_key_value"     "$em_build_container" "$em_target_image" >/dev/null
  docker rm "$em_build_container" >/dev/null
  trap - EXIT HUP INT TERM

  em_committed_base=$(em_image_label "$em_target_image" "$EM_APPLICATION_BASE_LABEL")
  if [ "$em_committed_base" != "$em_base_key_value" ]; then
    echo "[ERROR] 应用镜像缺少正确的依赖基线标记" >&2
    return 1
  fi
}

em_cleanup_previous_application_image() {
  em_previous_image_id=$1
  em_current_image=$2

  [ -n "$em_previous_image_id" ] || return 0
  em_current_image_id=$(em_image_id "$em_current_image")
  [ "$em_previous_image_id" != "$em_current_image_id" ] || return 0
  docker image inspect "$em_previous_image_id" >/dev/null 2>&1 || return 0

  for em_container_id in $(docker ps -aq); do
    em_container_image_id=$(docker inspect --format '{{.Image}}' "$em_container_id" 2>/dev/null || true)
    if [ "$em_container_image_id" = "$em_previous_image_id" ]; then
      echo "[WARN] 保留仍被容器引用的旧应用镜像：$em_previous_image_id" >&2
      return 0
    fi
  done

  em_repo_tags=$(docker image inspect --format '{{if .RepoTags}}{{join .RepoTags ","}}{{end}}' "$em_previous_image_id" 2>/dev/null || true)
  if [ -n "$em_repo_tags" ]; then
    echo "[WARN] 保留仍有标签的旧应用镜像：$em_previous_image_id" >&2
    return 0
  fi

  docker image rm "$em_previous_image_id" >/dev/null 2>&1 ||     echo "[WARN] 旧应用镜像未能清理，已保留：$em_previous_image_id" >&2
}

