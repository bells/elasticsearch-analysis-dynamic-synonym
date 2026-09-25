#!/usr/bin/env python3
"""Check the distributable ZIP and source version alignment using only the stdlib."""
from pathlib import Path
from io import BytesIO
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = ET.parse(root / "pom.xml").getroot()
version = pom.find("m:version", ns).text
archive = root / f"target/releases/elasticsearch-analysis-dynamic-synonym-{version}.zip"
with ZipFile(archive) as package:
    names = package.namelist()
    assert len(names) == len(set(names)), "Duplicate ZIP entries"
    assert all("/" not in name for name in names), "Plugin contents must be at ZIP root"
    required = {"plugin-descriptor.properties", "plugin-security.policy", "LICENSE", "NOTICE",
                f"elasticsearch-analysis-dynamic-synonym-{version}.jar"}
    assert required <= set(names), f"Missing entries: {required - set(names)}"
    properties = dict(line.split("=", 1) for line in package.read("plugin-descriptor.properties").decode().splitlines()
                      if line.strip() and not line.lstrip().startswith("#") and "=" in line)
    for key, value in {"name": "analysis-dynamic-synonym", "version": version,
                       "elasticsearch.version": version, "java.version": "17",
                       "classname": "com.bellszhu.elasticsearch.plugin.DynamicSynonymPlugin"}.items():
        assert properties[key] == value, f"Unexpected {key}: {properties[key]}"
    assert not any("${" in value for value in properties.values()), "Unresolved property"
    for dependency in ("httpclient5-", "httpcore5-", "analysis-common-"):
        assert any(name.startswith(dependency) for name in names), f"Missing {dependency}"
    for name in names:
        assert not re.match(r"(?:elasticsearch-\d|lucene-|log4j-|junit-|hamcrest-|elasticsearch-cluster-runner-)", name), name
    with ZipFile(BytesIO(package.read(f"elasticsearch-analysis-dynamic-synonym-{version}.jar"))) as project:
        for name in project.namelist():
            if name.endswith(".class"):
                assert int.from_bytes(project.read(name)[6:8], "big") == 61, f"Not Java 17 bytecode: {name}"
    print(f"Verified {archive.name}: {len(names)} entries, ES {version}, Java 17")
docker = (root / "Dockerfile").read_text()
assert f"elasticsearch:{version}" in docker, "Docker/POM Elasticsearch version mismatch"
assert "eclipse-temurin-17" in docker, "Docker must compile with JDK 17"
package_docker = (root / "Dockerfile.package").read_text()
assert f"elasticsearch:{version}" in package_docker, "Package Docker/POM Elasticsearch version mismatch"
assert "!src/**" in (root / ".dockerignore").read_text(), "Docker build must include tests"
assert "!target/package-image/**" in (root / ".dockerignore").read_text(), "Docker build must include packaged plugin"
