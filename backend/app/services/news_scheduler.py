import asyncio
import datetime
import logging
from typing import Optional
from sqlalchemy import select

from app.core.config import settings
from app.core.database import AsyncSessionLocal
from app.models.article import Article
from app.models.category import Category
from app.services.news_briefing_service import (
    BRIEFING_CATEGORY_NAME,
    generate_and_save_daily_briefing,
)

logger = logging.getLogger("podoc.news_scheduler")


class DailyNewsScheduler:
    """
    Bộ lập lịch chạy ngầm tự động tạo Bản tin sáng lúc 07:00 AM (giờ Việt Nam, UTC+7).
    """

    def __init__(self):
        self._task: Optional[asyncio.Task] = None
        self._running = False

    def start(self):
        """Khởi động bộ lập lịch."""
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._run_loop())
        logger.info("Bộ lập lịch Điểm tin sáng 07:00 AM đã được khởi động.")

    async def stop(self):
        """Dừng bộ lập lịch."""
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        logger.info("Bộ lập lịch Điểm tin sáng đã dừng.")

    def _seconds_until_target(self, target_hour: int = 7, target_minute: int = 0) -> float:
        """Tính số giây từ thời điểm hiện tại đến 07:00 AM (UTC+7) tiếp theo."""
        vn_tz = datetime.timezone(datetime.timedelta(hours=7))
        now_vn = datetime.datetime.now(vn_tz)
        target_today = now_vn.replace(hour=target_hour, minute=target_minute, second=0, microsecond=0)

        if now_vn >= target_today:
            # Đã qua 7h sáng hôm nay, hẹn 7h sáng ngày mai
            target_time = target_today + datetime.timedelta(days=1)
        else:
            target_time = target_today

        diff = (target_time - now_vn).total_seconds()
        return max(5.0, diff)

    async def _has_briefing_for_today(self) -> bool:
        """Kiểm tra xem ngày hôm nay đã có bản tin sáng nào được tạo chưa để tránh trùng lặp."""
        vn_tz = datetime.timezone(datetime.timedelta(hours=7))
        now_vn = datetime.datetime.now(vn_tz)
        today_prefix = f"Điểm tin sáng - {now_vn.strftime('%d/%m/%Y')}"

        async with AsyncSessionLocal() as db:
            stmt = select(Article).join(Category).where(
                Category.name == BRIEFING_CATEGORY_NAME,
                Article.title.ilike(f"%{now_vn.strftime('%d/%m/%Y')}%")
            )
            res = await db.execute(stmt)
            return res.scalar_one_or_none() is not None

    async def _run_loop(self):
        """Vòng lặp chạy ngầm."""
        # Chờ 15s sau khi khởi động app để hệ thống ổn định
        await asyncio.sleep(15)

        while self._running:
            try:
                secs = self._seconds_until_target(target_hour=7, target_minute=0)
                hours = secs / 3600
                logger.info(f"Điểm tin sáng: Bản tin tiếp theo sẽ chạy sau {hours:.2f} giờ nữa (lúc 07:00 AM UTC+7).")

                # Chờ đến giờ hẹn
                await asyncio.sleep(secs)

                if not self._running:
                    break

                # Kiểm tra nếu hôm nay chưa chạy thì chạy
                already_has = await self._has_briefing_for_today()
                if not already_has:
                    logger.info("Đã đến 07:00 AM! Bắt đầu tạo Điểm tin sáng tự động...")
                    await generate_and_save_daily_briefing()
                else:
                    logger.info("Hôm nay đã có bản tin sáng, bỏ qua lượt chạy tự động.")

                # Đợi thêm 60s để tránh chạy trùng trong cùng 1 phút
                await asyncio.sleep(60)

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Lỗi trong vòng lặp DailyNewsScheduler: {e}")
                # Nếu có lỗi, đợi 5 phút rồi tiếp tục
                await asyncio.sleep(300)


# Singleton scheduler instance
daily_news_scheduler = DailyNewsScheduler()

