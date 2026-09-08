#!/usr/bin/env python3
"""Convert a KML polygon into a georeferenced extruded solid (IFC4 + GLB)."""

from __future__ import annotations

import argparse
import math
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree as ET

import numpy as np
import trimesh


def parse_kml_ring(path: Path) -> list[tuple[float, float, float]]:
    root = ET.parse(path).getroot()
    for el in root.iter():
        if "}" in el.tag:
            el.tag = el.tag.split("}", 1)[1]
    coords_el = root.find(".//Polygon/outerBoundaryIs/LinearRing/coordinates")
    if coords_el is None or not (coords_el.text or "").strip():
        coords_el = root.find(".//coordinates")
    if coords_el is None or not (coords_el.text or "").strip():
        raise SystemExit(f"No coordinates in {path}")
    pts: list[tuple[float, float, float]] = []
    for token in re.split(r"\s+", coords_el.text.strip()):
        if "," not in token:
            continue
        parts = token.split(",")
        lon = float(parts[0])
        lat = float(parts[1])
        alt = float(parts[2]) if len(parts) > 2 and parts[2] else 0.0
        pts.append((lon, lat, alt))
    if len(pts) >= 2 and pts[0][:2] == pts[-1][:2]:
        pts = pts[:-1]
    if len(pts) < 3:
        raise SystemExit("Polygon needs at least 3 vertices")
    return pts


def wgs84_to_utm(lon: float, lat: float) -> tuple[float, float, int, bool]:
    """Return easting, northing, zone number, northern_hemisphere."""
    zone = int((lon + 180) // 6) + 1
    northern = lat >= 0
    # Simplified transverse Mercator (WGS84) — enough for BIM georef
    a = 6378137.0
    f = 1 / 298.257223563
    e2 = f * (2 - f)
    ep2 = e2 / (1 - e2)
    lon0 = math.radians((zone - 1) * 6 - 180 + 3)
    lat_r = math.radians(lat)
    lon_r = math.radians(lon)
    N = a / math.sqrt(1 - e2 * math.sin(lat_r) ** 2)
    T = math.tan(lat_r) ** 2
    C = ep2 * math.cos(lat_r) ** 2
    A = (lon_r - lon0) * math.cos(lat_r)
    M = a * (
        (1 - e2 / 4 - 3 * e2**2 / 64 - 5 * e2**3 / 256) * lat_r
        - (3 * e2 / 8 + 3 * e2**2 / 32 + 45 * e2**3 / 1024) * math.sin(2 * lat_r)
        + (15 * e2**2 / 256 + 45 * e2**3 / 1024) * math.sin(4 * lat_r)
        - (35 * e2**3 / 3072) * math.sin(6 * lat_r)
    )
    k0 = 0.9996
    easting = (
        k0
        * N
        * (
            A
            + (1 - T + C) * A**3 / 6
            + (5 - 18 * T + T**2 + 72 * C - 58 * ep2) * A**5 / 120
        )
        + 500000.0
    )
    northing = k0 * (
        M
        + N
        * math.tan(lat_r)
        * (
            A**2 / 2
            + (5 - T + 9 * C + 4 * C**2) * A**4 / 24
            + (61 - 58 * T + T**2 + 600 * C - 330 * ep2) * A**6 / 720
        )
    )
    if not northern:
        northing += 10000000.0
    return easting, northing, zone, northern


def to_local_meters(
    lonlat: list[tuple[float, float, float]],
) -> tuple[np.ndarray, float, float, float, float, int, bool]:
    lons = np.array([p[0] for p in lonlat])
    lats = np.array([p[1] for p in lonlat])
    clon = float(lons.mean())
    clat = float(lats.mean())
    e0, n0, zone, northern = wgs84_to_utm(clon, clat)
    local = []
    for lon, lat, _alt in lonlat:
        e, n, _, _ = wgs84_to_utm(lon, lat)
        local.append((e - e0, n - n0))
    xy = np.array(local, dtype=float)
    return xy, clon, clat, e0, n0, zone, northern


def extrude_mesh(xy: np.ndarray, height: float) -> trimesh.Trimesh:
    area = 0.0
    for i in range(len(xy)):
        j = (i + 1) % len(xy)
        area += xy[i, 0] * xy[j, 1] - xy[j, 0] * xy[i, 1]
    if area < 0:
        xy = xy[::-1].copy()

    n = len(xy)
    bottom = np.column_stack([xy, np.zeros(n)])
    top = np.column_stack([xy, np.full(n, height)])
    vertices = np.vstack([bottom, top])
    faces: list[list[int]] = []
    for i in range(1, n - 1):
        faces.append([0, i + 1, i])
    for i in range(1, n - 1):
        faces.append([n, n + i, n + i + 1])
    for i in range(n):
        j = (i + 1) % n
        faces.append([i, j, n + j])
        faces.append([i, n + j, n + i])
    mesh = trimesh.Trimesh(vertices=vertices, faces=np.array(faces), process=True)
    mesh.visual.face_colors = [52, 211, 153, 220]
    return mesh


def write_glb(mesh: trimesh.Trimesh, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mesh.export(path, file_type="glb")


def deg_to_dms(value: float) -> tuple[int, int, int, int]:
    sign = 1 if value >= 0 else -1
    value = abs(value)
    d = int(value)
    m_float = (value - d) * 60
    m = int(m_float)
    s = (m_float - m) * 60
    return (sign * d, m, int(s), int(round((s - int(s)) * 1_000_000)))


def write_georeferenced_ifc(
    xy: np.ndarray,
    height: float,
    clon: float,
    clat: float,
    easting: float,
    northing: float,
    zone: int,
    northern: bool,
    path: Path,
    name: str,
) -> None:
    """
    IFC4 with IfcProjectedCRS + IfcMapConversion (UTM) and IfcSite lat/lon.
    Geometry in metres, local EN relative to map conversion origin.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    pts = [tuple(map(float, p)) for p in xy]
    if pts[0] != pts[-1]:
        pts = pts + [pts[0]]

    # Ensure CCW
    area = sum(pts[i][0] * pts[i + 1][1] - pts[i + 1][0] * pts[i][1] for i in range(len(pts) - 1))
    if area < 0:
        pts = pts[::-1]

    ep = 32700 + zone if not northern else 32600 + zone
    crs_name = f"EPSG:{ep}"
    lat_dms = deg_to_dms(clat)
    lon_dms = deg_to_dms(clon)
    guid = lambda: uuid.uuid4().hex.upper()  # noqa: E731
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")

    lines: list[str] = []
    eid = 1

    def A(entity: str) -> int:
        nonlocal eid
        cur = eid
        lines.append(f"#{cur}={entity};")
        eid += 1
        return cur

    person = A("IFCPERSON($,'CampoAR',$,$,$,$,$,$)")
    org = A("IFCORGANIZATION($,'CampoAR','Georeferenced KML extrusion',$,$)")
    person_org = A(f"IFCPERSONANDORGANIZATION(#{person},#{org},$)")
    application = A(f"IFCAPPLICATION(#{org},'1.0','CampoAR','CampoAR')")
    owner = A(
        f"IFCOWNERHISTORY(#{person_org},#{application},$,.ADDED.,$,$,$,0)"
    )

    u_len = A("IFCSIUNIT(*,.LENGTHUNIT.,$,.METRE.)")
    u_area = A("IFCSIUNIT(*,.AREAUNIT.,$,.SQUARE_METRE.)")
    u_vol = A("IFCSIUNIT(*,.VOLUMEUNIT.,$,.CUBIC_METRE.)")
    units = A(f"IFCUNITASSIGNMENT((#{u_len},#{u_area},#{u_vol}))")

    origin = A("IFCCARTESIANPOINT((0.,0.,0.))")
    dir_z = A("IFCDIRECTION((0.,0.,1.))")
    dir_x = A("IFCDIRECTION((1.,0.,0.))")
    world = A(f"IFCAXIS2PLACEMENT3D(#{origin},#{dir_z},#{dir_x})")
    true_north = A("IFCDIRECTION((0.,1.))")
    geom_ctx = A(
        f"IFCGEOMETRICREPRESENTATIONCONTEXT($,'Model',3,1.E-05,#{world},#{true_north})"
    )
    body_ctx = A(
        f"IFCGEOMETRICREPRESENTATIONSUBCONTEXT('Body','Model',*,*,*,*,#{geom_ctx},$,.MODEL_VIEW.,$)"
    )

    project = A(
        f"IFCPROJECT('{guid()}',#{owner},'{name}',"
        f"'Georeferenced solid from KML lon={clon:.8f} lat={clat:.8f}',"
        f"$,$,$,(#{geom_ctx}),#{units})"
    )

    # Site placement + lat/lon (IFC compound angle)
    site_place = A(f"IFCLOCALPLACEMENT($,#{world})")
    site = A(
        f"IFCSITE('{guid()}',#{owner},'Site Arequipa',"
        f"'WGS84 {clat:.8f},{clon:.8f} / {crs_name}',"
        f"$,#{site_place},$,$,.ELEMENT.,"
        f"({lat_dms[0]},{lat_dms[1]},{lat_dms[2]},{lat_dms[3]}),"
        f"({lon_dms[0]},{lon_dms[1]},{lon_dms[2]},{lon_dms[3]}),"
        f"0.,$,$)"
    )
    building = A(
        f"IFCBUILDING('{guid()}',#{owner},'Building',$,$,#{site_place},$,$,.ELEMENT.,$,$,$)"
    )
    storey_place = A(f"IFCLOCALPLACEMENT(#{site_place},#{world})")
    storey = A(
        f"IFCBUILDINGSTOREY('{guid()}',#{owner},'Level 0',$,$,#{storey_place},$,$,.ELEMENT.,0.)"
    )
    A(f"IFCRELAGGREGATES('{guid()}',#{owner},$,$,#{project},(#{site}))")
    A(f"IFCRELAGGREGATES('{guid()}',#{owner},$,$,#{site},(#{building}))")
    A(f"IFCRELAGGREGATES('{guid()}',#{owner},$,$,#{building},(#{storey}))")

    # --- Georeferencing (IFC4) ---
    projected_crs = A(
        f"IFCPROJECTEDCRS('{crs_name}','WGS 84 / UTM zone {zone}"
        f"{'N' if northern else 'S'}',$,'EPSG','{ep}',$,#{u_len})"
    )
    # MapConversion: Eastings, Northings, OrthogonalHeight, XAxisAbscissa, XAxisOrdinate, Scale
    # Local +X = East, +Y = North
    map_conv = A(
        f"IFCMAPCONVERSION(#{geom_ctx},#{projected_crs},"
        f"{easting:.3f},{northing:.3f},0.,1.,0.,1.)"
    )

    point_ids = [A(f"IFCCARTESIANPOINT(({x:.6f},{y:.6f}))") for x, y in pts]
    poly = A("IFCPOLYLINE((%s))" % ",".join(f"#{i}" for i in point_ids))
    profile = A(f"IFCARBITRARYCLOSEDPROFILEDEF(.AREA.,'Footprint',#{poly})")
    extrude_dir = A("IFCDIRECTION((0.,0.,1.))")
    solid = A(
        f"IFCEXTRUDEDAREASOLID(#{profile},#{world},#{extrude_dir},{height:.3f})"
    )
    shape_rep = A(
        f"IFCSHAPEREPRESENTATION(#{body_ctx},'Body','SweptSolid',(#{solid}))"
    )
    prod_shape = A(f"IFCPRODUCTDEFINITIONSHAPE($,$,(#{shape_rep}))")
    proxy_place = A(f"IFCLOCALPLACEMENT(#{storey_place},#{world})")
    desc = (
        f"Extruded {height}m georef {crs_name} E={easting:.3f} N={northing:.3f} "
        f"WGS84={clat:.8f},{clon:.8f}"
    )
    proxy = A(
        f"IFCBUILDINGELEMENTPROXY('{guid()}',#{owner},"
        f"'{name}','{desc}',$,#{proxy_place},#{prod_shape},$,.USERDEFINED.)"
    )
    A(
        f"IFCRELCONTAINEDINSPATIALSTRUCTURE('{guid()}',#{owner},$,$,(#{proxy}),#{storey})"
    )

    # Keep map_conv referenced so optimizers don't strip it
    _ = map_conv

    header = f"""ISO-10303-21;
HEADER;
FILE_DESCRIPTION(('ViewDefinition [CoordinationView]','Georeferencing'),'2;1');
FILE_NAME('{path.name}','{stamp}',('CampoAR'),('CampoAR'),'CampoAR kml_to_solid','CampoAR','');
FILE_SCHEMA(('IFC4'));
ENDSEC;
DATA;
"""
    path.write_text(header + "\n".join(lines) + "\nENDSEC;\nEND-ISO-10303-21;\n", encoding="utf-8")


def write_meta(
    path: Path,
    clon: float,
    clat: float,
    height: float,
    verts: int,
    easting: float,
    northing: float,
    epsg: int,
) -> None:
    path.write_text(
        "\n".join(
            [
                f"centroid_lon={clon}",
                f"centroid_lat={clat}",
                f"height_m={height}",
                f"vertices={verts}",
                f"utm_easting={easting}",
                f"utm_northing={northing}",
                f"epsg={epsg}",
                "",
            ]
        ),
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("kml", type=Path)
    parser.add_argument("--height", type=float, default=3.0)
    parser.add_argument("--ifc", type=Path, required=True)
    parser.add_argument("--glb", type=Path, required=True)
    parser.add_argument("--name", default="Poligono_extrusion")
    args = parser.parse_args()

    ring = parse_kml_ring(args.kml)
    xy, clon, clat, e0, n0, zone, northern = to_local_meters(ring)
    epsg = (32700 if not northern else 32600) + zone
    mesh = extrude_mesh(xy, args.height)
    write_glb(mesh, args.glb)
    write_georeferenced_ifc(
        xy, args.height, clon, clat, e0, n0, zone, northern, args.ifc, args.name
    )
    write_meta(
        args.glb.with_suffix(".meta.txt"),
        clon,
        clat,
        args.height,
        len(ring),
        e0,
        n0,
        epsg,
    )
    # Also copy IFC next to GLB assets for the phone
    asset_ifc = args.glb.with_suffix(".ifc")
    asset_ifc.write_bytes(args.ifc.read_bytes())

    print(f"GLB: {args.glb} ({args.glb.stat().st_size} bytes)")
    print(f"IFC: {args.ifc} ({args.ifc.stat().st_size} bytes)")
    print(f"IFC asset copy: {asset_ifc}")
    print(f"WGS84 centroid: {clat:.8f}, {clon:.8f}")
    print(f"UTM EPSG:{epsg} E={e0:.3f} N={n0:.3f}")
    print(f"footprint: {xy[:,0].ptp():.2f}m x {xy[:,1].ptp():.2f}m x {args.height}m")


if __name__ == "__main__":
    main()
