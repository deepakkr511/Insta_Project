from fastapi import APIRouter, Depends
from sqlalchemy.orm.session import Session
from routers.schemas import UserDisplay,UserBase
from db.database import get_db
from db import db_user

router= APIRouter(
    prefix='/user',
    tags=['user']
)

#creating endpoint
@router.post('',response_model=UserDisplay)#i want to have user display model that is defined in schemas
def create_user(request: UserBase, db:Session=Depends(get_db)):#get_db is from database.py
    return db_user.create_user(db,request)#this create user is from db_user
