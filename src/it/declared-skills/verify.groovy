assert new File(basedir, '.claude/skills/demo/SKILL.md').isFile()
assert new File(basedir, '.github/skills/demo/SKILL.md').isFile()
def lock = new File(basedir, 'skill-sommelier.lock.json').text
assert !lock.contains('"old"') : 'entry whose declaration was removed must be dropped'
assert lock.count('"declared" : true') == 2
def log = new File(basedir, 'build.log').text
assert log.contains('Skill sync: 2 installed, 1 uninstalled')
assert log.contains('Skill check: 2 up to date')
