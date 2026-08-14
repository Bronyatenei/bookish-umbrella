package com.twitchalarm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.twitchalarm.R
import com.twitchalarm.data.Streamer
import com.twitchalarm.databinding.ItemStreamerBinding

class StreamerAdapter(
    private val onToggle:    (Streamer, Boolean) -> Unit,
    private val onDelete:    (Streamer) -> Unit,
    private val onTestAlarm: (Streamer) -> Unit
) : ListAdapter<Streamer, StreamerAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemStreamerBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(streamer: Streamer) {
            val displayName = streamer.displayName.ifEmpty { streamer.login }

            b.tvName.text  = displayName
            b.tvLogin.text = "@${streamer.login}"

            // Статус live / offline
            if (streamer.isLive) {
                b.viewLiveIndicator.setBackgroundResource(R.drawable.bg_live)
                b.tvStatus.text      = "🔴 В ЭФИРЕ"
                b.tvStatus.setTextColor(ContextCompat.getColor(b.root.context, R.color.live_red))

                b.tvStreamInfo.visibility = View.VISIBLE
                val viewers = when {
                    streamer.viewerCount >= 1_000_000 -> "${streamer.viewerCount / 1_000_000}M зрителей"
                    streamer.viewerCount >= 1_000     -> "${streamer.viewerCount / 1_000}K зрителей"
                    else                              -> "${streamer.viewerCount} зрителей"
                }
                val game = if (streamer.gameName.isNotEmpty()) " · ${streamer.gameName}" else ""
                b.tvStreamInfo.text = "$viewers$game"

                b.tvStreamTitle.visibility = View.VISIBLE
                b.tvStreamTitle.text       = streamer.streamTitle.ifEmpty { "" }
            } else {
                b.viewLiveIndicator.setBackgroundResource(R.drawable.bg_offline)
                b.tvStatus.text      = "⚫ Офлайн"
                b.tvStatus.setTextColor(ContextCompat.getColor(b.root.context, R.color.offline_grey))
                b.tvStreamInfo.visibility  = View.GONE
                b.tvStreamTitle.visibility = View.GONE
            }

            // Сначала отключаем прежний listener: иначе isChecked при переиспользовании
            // ViewHolder может ошибочно записать значение для другого стримера.
            b.switchNotify.setOnCheckedChangeListener(null)
            b.switchNotify.isChecked = streamer.notifyEnabled
            b.switchNotify.setOnCheckedChangeListener { _, checked ->
                onToggle(streamer, checked)
            }

            // Кнопка удаления
            b.btnDelete.setOnClickListener { onDelete(streamer) }

            // Тест будильника (долгое нажатие на карточку)
            b.root.setOnLongClickListener {
                onTestAlarm(streamer)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemStreamerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Streamer>() {
            override fun areItemsTheSame(a: Streamer, b: Streamer) = a.login == b.login
            override fun areContentsTheSame(a: Streamer, b: Streamer) = a == b
        }
    }
}
