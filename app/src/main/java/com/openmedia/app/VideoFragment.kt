package com.openmedia.app

import android.os.Bundle
import android.view.View
import android.widget.ExpandableListView
import android.widget.TextView
import androidx.fragment.app.Fragment

class VideoFragment : Fragment(R.layout.fragment_video), Refreshable {
    private lateinit var list: ExpandableListView
    private lateinit var empty: TextView
    private var adapter: FolderAdapter? = null
    private val expanded = HashSet<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        list.setOnGroupExpandListener { g -> adapter?.getGroup(g)?.let { expanded += it.path } }
        list.setOnGroupCollapseListener { g -> adapter?.getGroup(g)?.let { expanded -= it.path } }
        list.setOnChildClickListener { _, _, g, c, _ ->
            adapter?.let { startActivity(PlayerActivity.intent(requireContext(), it.getChild(g, c))) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        val ctx = context ?: return
        if (!::list.isInitialized) return
        if (!Perm.has(ctx)) {
            empty.text = "Allow media access to find your videos."
            empty.visibility = View.VISIBLE
            return
        }
        val app = ctx.applicationContext
        Bg.io.execute {
            val folders = MediaRepo.loadFolders(app)
            Bg.main.post { if (isAdded && view != null) show(folders) }
        }
    }

    private fun show(folders: List<Folder>) {
        if (adapter?.data == folders) return
        val idx = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        val a = FolderAdapter(requireContext(), folders)
        adapter = a
        list.setAdapter(a)
        folders.forEachIndexed { i, f -> if (f.path in expanded) list.expandGroup(i) }
        list.setSelectionFromTop(idx, top)
        empty.text = "No videos found on this device."
        empty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
    }
}
