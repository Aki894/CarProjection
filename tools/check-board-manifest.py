import sys,xml.etree.ElementTree as ET,zipfile
root=ET.parse(sys.argv[1]).getroot();a='{http://schemas.android.com/apk/res/android}'
assert root.attrib['package']=='com.projection.car'
components=[x.attrib.get(a+'name','') for tag in ['activity','service'] for x in root.findall('application/'+tag)]
assert 'com.projection.car.BoardSessionService' in components
assert not any(x.endswith(('.MainActivity','.ForgroundService','.ProjectionService','.CarLifeSessionService')) for x in components)
perms=[x.attrib[a+'name'] for x in root.findall('uses-permission')]
assert 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION' not in perms
assert 'android.permission.WRITE_SETTINGS' not in perms
bridge=root.find('application/service[@'+a+'name="com.projection.car.CarPlayAudioBridgeService"]')
assert bridge is not None and bridge.attrib[a+'permission']=='com.projection.car.permission.BOARD_CONTROL'
with zipfile.ZipFile(sys.argv[2]) as z:
 assert all(not n.startswith('lib/') or n.startswith('lib/armeabi-v7a/') for n in z.namelist())
 assert not any(n.endswith('.apk') for n in z.namelist())
print('PASS: board service, signature-protected data bridge, ARM32 and no capture/accessibility/launcher components')
