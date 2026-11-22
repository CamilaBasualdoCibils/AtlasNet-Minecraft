# Stack Tool sets fork tracking before cppdbg loads the executable. Override
# it as the node's symbols load, without competing with cppdbg's entry breakpoint.
python
def detach_minecraft_shard_workers(event):
    executable = event.new_objfile.progspace.filename
    if executable and executable.rsplit("/", 1)[-1] == "AtlasNetNode":
        gdb.execute("set detach-on-fork on")

gdb.events.new_objfile.connect(detach_minecraft_shard_workers)
end
