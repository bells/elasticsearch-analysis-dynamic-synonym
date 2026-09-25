#!/usr/bin/env python3
"""Check the distributable ZIP and source version alignment using only the stdlib."""
from pathlib import Path
from io import BytesIO
import argparse
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = ET.parse(root / "pom.xml").getroot()
parser = argparse.ArgumentParser()
parser.add_argument("--version", default=pom.find("m:properties/m:revision", ns).text)
args = parser.parse_args()
version = args.version
assert version in {"9.3.4", "9.5.4"}, f"Unsupported Elasticsearch version: {version}"
archive = root / f"target/releases/elasticsearch-analysis-dynamic-synonym-{version}.zip"
with ZipFile(archive) as package:
    names = package.namelist()
    assert len(names) == len(set(names)), "Duplicate ZIP entries"
    assert all("/" not in name for name in names), "Plugin contents must be at ZIP root"
    required = {"plugin-descriptor.properties", "entitlement-policy.yaml", "LICENSE", "NOTICE",
                f"elasticsearch-analysis-dynamic-synonym-{version}.jar"}
    assert required <= set(names), f"Missing entries: {required - set(names)}"
    assert "plugin-security.policy" not in names, "Legacy SecurityManager policy is unsupported for ES 9"
    entitlements = package.read("entitlement-policy.yaml").decode()
    assert entitlements.splitlines() == ["ALL-UNNAMED:", "  - manage_threads", "  - outbound_network"]
    properties = dict(line.split("=", 1) for line in package.read("plugin-descriptor.properties").decode().splitlines()
                      if line.strip() and not line.lstrip().startswith("#") and "=" in line)
    for key, value in {"name": "analysis-dynamic-synonym", "version": version,
                       "elasticsearch.version": version, "java.version": "21",
                       "classname": "com.bellszhu.elasticsearch.plugin.DynamicSynonymPlugin"}.items():
        assert properties[key] == value, f"Unexpected {key}: {properties[key]}"
    assert not any("${" in value for value in properties.values()), "Unresolved property"
    for dependency in ("httpclient5-", "httpcore5-"):
        assert any(name.startswith(dependency) for name in names), f"Missing {dependency}"
    for name in names:
        assert not re.match(r"(?:elasticsearch-\d|lucene-|log4j-|junit-|hamcrest-|elasticsearch-cluster-runner-)", name), name
    with ZipFile(BytesIO(package.read(f"elasticsearch-analysis-dynamic-synonym-{version}.jar"))) as project:
        for name in project.namelist():
            if name.endswith(".class"):
                assert int.from_bytes(project.read(name)[6:8], "big") == 65, f"Not Java 21 bytecode: {name}"
    print(f"Verified {archive.name}: {len(names)} entries, ES {version}, Java 21")
docker = (root / "Dockerfile").read_text()
assert "ARG ES_VERSION=9.5.4" in docker and "elasticsearch:${ES_VERSION}" in docker
assert "eclipse-temurin-21" in docker, "Docker must compile with JDK 21"
assert "plugins/analysis-dynamic-synonym/" in docker, "Docker plugin directory must match descriptor name"
package_docker = (root / "Dockerfile.package").read_text()
assert "ARG ES_VERSION=9.5.4" in package_docker and "elasticsearch:${ES_VERSION}" in package_docker
assert "plugins/analysis-dynamic-synonym/" in package_docker, "Package Docker plugin directory mismatch"
assert "!src/**" in (root / ".dockerignore").read_text(), "Docker build must include tests"
assert "!target/package-image/**" in (root / ".dockerignore").read_text(), "Docker build must include packaged plugin"
